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
        } + found.values.mapNotNull { n ->
            // moved to another folder only: keep the hash, update the folder
            val o = existing[n.mediaStoreId] ?: return@mapNotNull null
            if (o.size == n.size && o.modified == n.modified && (o.bucket != n.bucket || o.relPath != n.relPath))
                o.copy(bucket = n.bucket, relPath = n.relPath) else null
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
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(cols[0]); val iName = c.getColumnIndexOrThrow(cols[1])
            val iSize = c.getColumnIndexOrThrow(cols[2]); val iMod = c.getColumnIndexOrThrow(cols[3])
            val iTaken = c.getColumnIndexOrThrow(cols[4]); val iMime = c.getColumnIndexOrThrow(cols[5])
            val iW = c.getColumnIndexOrThrow(cols[6]); val iH = c.getColumnIndexOrThrow(cols[7])
            val iAdded = c.getColumnIndexOrThrow(cols[8])
            val iBucket = c.getColumnIndexOrThrow(cols[9])
            val iRel = c.getColumnIndexOrThrow(cols[10])
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
                    bucket = c.getString(iBucket), relPath = c.getString(iRel),
                )
            }
        }
    }

    /** Phone folders -> the person's albums on the NAS (add-only; matched by file hash). */
    suspend fun pushFolderAlbums(api: com.photohub.app.data.api.PhotoHubApi): Int {
        var linked = 0
        val onNas = dao.remoteFileHashes().toHashSet()
        for (b in dao.bucketNames()) {
            if (isCameraRoll(b, dao.pathOf(b))) continue
            // only photos not sent for this folder yet; a photo not on the Synology yet is kept back
            // until it is there (the Synology can only link what it has)
            val sent = (ctx.applicationContext as com.photohub.app.PhotoHubApp).delta.sent("folder-$b")
            val todo = sent.notSent(dao.hashesIn(b)) { it }.filter { it in onNas }
            for (chunk in todo.chunked(5000)) {
                linked += api.deviceAlbum(com.photohub.app.data.api.DeviceAlbumBody(b, chunk)).added
                sent.addAll(chunk)
            }
        }
        return linked
    }

    /** Hash files that have none yet (run while charging). Returns how many were hashed. */
    suspend fun hashPending(limit: Int = 2000, isStopped: () -> Boolean = { false },
                            progress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        var n = 0
        val todo = dao.localWithoutHash(limit)
        for (m in todo) {
            if (isStopped()) break
            val uri = android.net.Uri.parse(m.uri)
            // the original bytes (with GPS) need ACCESS_MEDIA_LOCATION + setRequireOriginal
            val readUri = if (Build.VERSION.SDK_INT >= 29) MediaStore.setRequireOriginal(uri) else uri
            val hash = try {
                ctx.contentResolver.openInputStream(readUri)?.use { sha256(it) }
            } catch (e: SecurityException) {
                ctx.contentResolver.openInputStream(uri)?.use { sha256(it) }
            } catch (e: Exception) {
                null
            } ?: continue
            val (lat, lon) = if (m.kind == "photo") location(readUri) ?: location(uri) ?: (null to null) else (null to null)
            dao.setLocalHashAndPlace(m.mediaStoreId, hash, lat, lon)
            n++
            if (n % 20 == 0) progress(n, todo.size)
        }
        return n
    }

    /** Where the photo was taken (EXIF GPS), for the map. */
    private fun location(uri: android.net.Uri): Pair<Double?, Double?>? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            androidx.exifinterface.media.ExifInterface(s).latLong?.let { (it[0] to it[1]).takeIf { p -> p.first != 0.0 || p.second != 0.0 } }
        }
    } catch (e: Exception) { null }

    companion object {
        /** The camera roll holds nearly everything, so it is not sent to the NAS as an album. */
        fun isCameraRoll(bucket: String, relPath: String?): Boolean =
            bucket.equals("Camera", true) || relPath?.startsWith("DCIM/Camera", true) == true

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
