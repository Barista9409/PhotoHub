package com.photohub.app.data

import android.net.Uri
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.HashesBody
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Delete from everywhere: the Synology copy goes to PhotoHub's trash (30 days), the Google Drive copy
 * is removed, and the phone copies are returned so the screen can ask Android to delete them.
 */
object Deleter {
    data class Outcome(val trashed: Int, val fromDrive: Int, val phoneUris: List<Uri>, val error: String?)

    suspend fun everywhere(app: PhotoHubApp, items: List<TimelineItem>, nas: Boolean = true, drive: Boolean = true,
                           phone: Boolean = true): Outcome = withContext(Dispatchers.IO) {
        var error: String? = null
        var trashed = 0
        val hashes = if (nas) items.mapNotNull { it.hash } else emptyList()
        if (hashes.isNotEmpty()) {
            val api = app.net.api()
            if (api == null) error = "Synologyにつながらないため、Synologyの写真は消せませんでした"
            else try { trashed = api.deleteMedia(HashesBody(hashes))["trashed"] ?: 0 }
                 catch (e: Exception) { error = "Synologyで削除できませんでした（${e.message}）" }
        }
        var fromDrive = 0
        val onDrive = if (drive) items.mapNotNull { it.driveId } else emptyList()
        if (onDrive.isNotEmpty()) {
            val token = app.drive.token()
            if (token != null) for (id in onDrive) try { app.drive.delete(token, id); fromDrive++ } catch (_: Exception) {}
            val fh = app.db.media().driveFiles().filter { it.driveId in onDrive }.map { it.fileHash }
            if (fh.isNotEmpty()) app.db.media().deleteDrive(fh)
        }
        if (hashes.isNotEmpty()) app.db.media().deleteRemote(hashes)   // gone from the list right away
        Outcome(trashed, fromDrive, if (phone) items.mapNotNull { it.localUri?.let(Uri::parse) } else emptyList(), error)
    }
}
