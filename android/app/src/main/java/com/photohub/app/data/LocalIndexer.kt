package com.photohub.app.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.photohub.app.data.db.LocalMedia
import com.photohub.app.data.db.MediaDao
import java.security.MessageDigest

/**
 * Indexes the phone's MediaStore. Phone photos are matched with NAS copies by whole-file
 * SHA-256 (the Synology Photos mobile backup stores the original bytes). The NAS's pixel hash
 * is not recomputed here: JPEG/HEIC decoders differ between Android and Pillow.
 */
class LocalIndexer(private val ctx: Context, private val dao: MediaDao) {

    suspend fun scan(): Int {
        val found = mutableMapOf<Long, LocalMedia>()
        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "photo", found)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", found)
        val existing = dao.allLocal().associateBy { it.mediaStoreId }
        // new or modified files; their fileHash is reset to null so they get re-hashed
        val changed = found.values.filter { n ->
            val o = existing[n.mediaStoreId]
            o == null || o.size != n.size || o.modified != n.modified
        }
        dao.upsertLocal(changed)
        val gone = existing.keys - found.keys
        if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.deleteLocal(it) }
        return changed.size
    }

    private fun query(uri: android.net.Uri, kind: String, out: MutableMap<Long, LocalMedia>) {
        val cols = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.DATE_TAKEN); add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.DATE_ADDED)
        }.toTypedArray()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(cols[0]); val iName = c.getColumnIndexOrThrow(cols[1])
            val iSize = c.getColumnIndexOrThrow(cols[2]); val iMod = c.getColumnIndexOrThrow(cols[3])
            val iTaken = c.getColumnIndexOrThrow(cols[4]); val iMime = c.getColumnIndexOrThrow(cols[5])
            val iW = c.getColumnIndexOrThrow(cols[6]); val iH = c.getColumnIndexOrThrow(cols[7])
            val iAdded = c.getColumnIndexOrThrow(cols[8])
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000)
                out[id] = LocalMedia(
                    mediaStoreId = id,
                    uri = ContentUris.withAppendedId(uri, id).toString(),
                    kind = kind, mime = c.getString(iMime), name = c.getString(iName) ?: "",
                    size = c.getLong(iSize), modified = c.getLong(iMod), takenAt = taken,
                    w = c.getInt(iW).takeIf { it > 0 }, h = c.getInt(iH).takeIf { it > 0 },
                    lat = null, lon = null, fileHash = null,
                )
            }
        }
    }

    /** Hash files that have none yet (run while charging). Returns how many were hashed. */
    suspend fun hashPending(limit: Int = 2000, isStopped: () -> Boolean = { false }): Int {
        var n = 0
        for (m in dao.localWithoutHash(limit)) {
            if (isStopped()) break
            val uri = android.net.Uri.parse(m.uri)
            val readUri = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
            val hash = try {
                ctx.contentResolver.openInputStream(readUri)?.use { sha256(it) }
            } catch (e: SecurityException) {
                ctx.contentResolver.openInputStream(uri)?.use { sha256(it) }
            } catch (e: Exception) {
                null
            } ?: continue
            dao.setLocalHash(m.mediaStoreId, hash)
            n++
        }
        return n
    }

    companion object {
        fun sha256(input: java.io.InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
