package com.photohub.app.data

import android.content.Context
import com.photohub.app.data.db.AlbumWithCount
import com.photohub.app.data.db.AppDb
import com.photohub.app.data.db.FolderOf
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * The big lists, kept in memory for the whole app: switching tabs shows them at once instead of
 * reading 15,000+ rows again. null = still loading (not "no photos").
 * The photo list is also kept in a file, so the next start shows the last list right away while
 * the database is read in the background.
 */
class Lists(ctx: Context, db: AppDb, scope: CoroutineScope) {
    private val dao = db.media()
    private val cache = TimelineCache(File(ctx.filesDir, "timeline.bin"))

    val timeline: StateFlow<List<TimelineItem>?> = flow {
        val t0 = System.currentTimeMillis()
        cache.read()?.let { AppLog.time("写真一覧（保存分を読む）", System.currentTimeMillis() - t0); emit(it) }
        var first = true
        emitAll(dao.timeline().calm().onEach {
            if (first) { first = false; AppLog.time("写真一覧（データベース・${it.size / 1000}千件台）", System.currentTimeMillis() - t0) }
            cache.write(it)
        })
    }.distinctUntilChanged().keepGoing().flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.Eagerly, null)
    val shared: StateFlow<List<TimelineItem>?> = dao.sharedSpace().calm().stateIn(scope, SharingStarted.Eagerly, null)
    val sharedFolders: StateFlow<List<FolderOf>?> = dao.sharedFolders().calm().stateIn(scope, SharingStarted.Eagerly, null)
    val albums: StateFlow<List<AlbumWithCount>?> = dao.albums().calm().stateIn(scope, SharingStarted.Eagerly, null)

    /** Another person signs in: forget the saved list. */
    fun clearCache() = cache.clear()
}

/**
 * The first list at once, later ones at most about once a second: while a sync writes thousands of
 * rows, the database would otherwise re-read the whole library after every page and the screen
 * would rebuild each time. Identical lists are skipped.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
private fun <T> Flow<T>.calm(): Flow<T> {
    var first = true
    return debounce { if (first) { first = false; 0L } else 800L }.distinctUntilChanged().keepGoing()
}

/** A failed read (e.g. the phone busy writing) is written down and tried again a moment later,
 *  instead of the list stopping for good. */
private fun <T> Flow<T>.keepGoing(): Flow<T> = retryWhen { e, attempt ->
    AppLog.error("一覧の読み込み", e)
    kotlinx.coroutines.delay(minOf(30_000L, 1000L * (attempt + 1)))
    true
}

/** The photo list in a small binary file (tens of thousands of rows read in a fraction of a second). */
private class TimelineCache(private val file: File) {
    private val version = 1

    fun read(): List<TimelineItem>? = try {
        if (!file.exists()) null else DataInputStream(file.inputStream().buffered(1 shl 16)).use { r ->
            if (r.readInt() != version) return null
            val n = r.readInt()
            ArrayList<TimelineItem>(n).apply {
                repeat(n) {
                    add(TimelineItem(
                        itemKey = r.readUTF(), hash = r.str(), localUri = r.str(), kind = r.readUTF(),
                        takenAt = r.readLong(), tz = r.int(), name = r.readUTF(), stores = r.readUTF(),
                        lat = r.dbl(), lon = r.dbl(), bytes = r.readLong(), w = r.int(), h = r.int(),
                        duration = r.dbl(), mime = r.str(), driveId = r.str(), place = r.str(), rot = r.readInt(),
                    ))
                }
            }
        }
    } catch (e: Exception) { file.delete(); null }

    fun write(items: List<TimelineItem>) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { w ->
                w.writeInt(version); w.writeInt(items.size)
                for (i in items) {
                    w.writeUTF(i.itemKey); w.str(i.hash); w.str(i.localUri); w.writeUTF(i.kind)
                    w.writeLong(i.takenAt); w.int(i.tz); w.writeUTF(i.name); w.writeUTF(i.stores)
                    w.dbl(i.lat); w.dbl(i.lon); w.writeLong(i.bytes); w.int(i.w); w.int(i.h)
                    w.dbl(i.duration); w.str(i.mime); w.str(i.driveId); w.str(i.place); w.writeInt(i.rot)
                }
            }
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    fun clear() { file.delete() }

    private fun DataInputStream.str(): String? = if (readBoolean()) readUTF() else null
    private fun DataInputStream.int(): Int? = if (readBoolean()) readInt() else null
    private fun DataInputStream.dbl(): Double? = if (readBoolean()) readDouble() else null
    private fun DataOutputStream.str(v: String?) { writeBoolean(v != null); if (v != null) writeUTF(v) }
    private fun DataOutputStream.int(v: Int?) { writeBoolean(v != null); if (v != null) writeInt(v) }
    private fun DataOutputStream.dbl(v: Double?) { writeBoolean(v != null); if (v != null) writeDouble(v) }
}
