package com.photohub.app.data

import android.net.Uri
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.HashesBody
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Delete from everywhere: the Synology copy goes to PhotoHub's trash (30 days), the Google Drive copy
 * is removed, and the phone copies are returned so the screen can ask Android to delete them.
 *
 * Feels instant: the photos leave the screen the moment 削除 is pressed ([hidden]); the Synology and
 * Drive work runs in the background, and if the Synology refuses, the photos come back with a message.
 */
object Deleter {
    data class Outcome(val phoneUris: List<Uri>, val hiddenKeys: Set<String>)

    /** Item keys taken off every grid at once, before the database and the Synology catch up. */
    val hidden = MutableStateFlow<Set<String>>(emptySet())

    fun unhide(keys: Collection<String>) { if (keys.isNotEmpty()) hidden.update { it - keys.toSet() } }

    /**
     * Starts the delete and returns at once. [report] is called on the main thread with a short
     * message when the background part ends (null = nothing worth saying).
     */
    fun start(app: PhotoHubApp, items: List<TimelineItem>, nas: Boolean = true, drive: Boolean = true,
              phone: Boolean = true, report: (String?) -> Unit = {}): Outcome {
        // gone from the screen only when every place it is in is being cleared
        val keys = items.filter { (it.hash == null || nas) && (it.driveId == null || drive) && (it.localUri == null || phone) }
            .map { it.itemKey }.toSet()
        hidden.update { it + keys }
        val hashes = if (nas) items.mapNotNull { it.hash }.distinct() else emptyList()
        val onDrive = if (drive) items.mapNotNull { it.driveId }.distinct() else emptyList()
        app.scope.launch(Dispatchers.IO) {
            val dao = app.db.media()
            var error: String? = null
            var trashed = 0
            var fromDrive = 0
            coroutineScope {
                val nasJob = async {
                    if (hashes.isEmpty()) return@async
                    val before = dao.remoteByHashes(hashes)   // to put back if the Synology says no
                    dao.deleteRemote(hashes)
                    val api = app.net.api()
                    try {
                        if (api == null) throw IllegalStateException("Synologyにつながりません")
                        hashes.chunked(500).forEach { trashed += api.deleteMedia(HashesBody(it))["trashed"] ?: 0 }
                    } catch (e: Exception) {
                        error = "Synologyで削除できませんでした（${e.message}）。元に戻しました"
                        dao.upsertRemote(before)
                        unhide(items.filter { it.hash != null }.map { it.itemKey })
                    }
                }
                val driveJob = async {
                    if (onDrive.isEmpty()) return@async
                    val token = app.drive.token()
                    if (token != null) fromDrive = onDrive.chunked(8).sumOf { part ->
                        part.map { id -> async { try { app.drive.delete(token, id); 1 } catch (_: Exception) { 0 } } }.awaitAll().sum()
                    }
                    val fh = dao.driveFiles().filter { it.driveId in onDrive }.map { it.fileHash }
                    if (fh.isNotEmpty()) dao.deleteDrive(fh)
                }
                listOf(nasJob, driveJob).awaitAll()
            }
            val msg = error ?: listOfNotNull(if (hashes.isNotEmpty()) "Synologyのゴミ箱に $trashed 件" else null,
                if (fromDrive > 0) "ドライブから $fromDrive 件" else null).joinToString("・").ifBlank { null }
            withContext(Dispatchers.Main) { report(msg) }
            SyncWorker.runNow(app, light = true)
            // the lists have caught up by now; a photo restored from the trash later must show again
            delay(5_000)
            unhide(keys.filter { !it.startsWith("local:") })
        }
        return Outcome(if (phone) items.mapNotNull { it.localUri?.let(Uri::parse) } else emptyList(), keys)
    }

    /** After Android's own delete: OK = drop the phone rows now (no full rescan); cancelled = show them again. */
    fun phoneDone(app: PhotoHubApp, uris: List<Uri>, keys: Set<String>, ok: Boolean) {
        if (!ok) { unhide(keys); return }
        app.scope.launch(Dispatchers.IO) {
            val ids = uris.mapNotNull { runCatching { android.content.ContentUris.parseId(it) }.getOrNull() }
            if (ids.isNotEmpty()) app.db.media().deleteLocal(ids)
            delay(3_000)
            unhide(keys.filter { it.startsWith("local:") })
        }
    }
}
