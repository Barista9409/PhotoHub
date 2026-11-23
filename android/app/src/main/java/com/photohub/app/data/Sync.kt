package com.photohub.app.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.HashesBody
import com.photohub.app.data.api.ThumbPack
import com.photohub.app.data.db.AlbumMedia
import com.photohub.app.data.db.AlbumRow
import com.photohub.app.data.db.RemoteMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Device sync (spec F6 "端末への同期"): at home on Wi-Fi and charging, pull the F7 delta,
 * all thumbnails, and previews for recent months / favourites / chosen albums, within the cap.
 */
class SyncEngine(private val app: PhotoHubApp) {
    private val prefs get() = app.prefs
    private val dao get() = app.db.media()
    private val files get() = app.files

    data class Result(val changed: Int, val thumbs: Int, val previews: Int, val message: String)

    suspend fun run(progress: suspend (String) -> Unit = {}, isStopped: () -> Boolean = { false }, listOnly: Boolean = false,
                    light: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val api = app.net.api(force = true) ?: return@withContext Result(0, 0, 0, "Synologyに接続できません")
            // phone folders -> albums on the NAS first, so the delta below already carries them
            try { app.indexer.pushFolderAlbums(api) } catch (_: Exception) {}
            var cursor = prefs.current().cursor
            var changed = 0
            // the album list comes only when it changed (the Synology compares its fingerprint)
            var albumsTag = if (cursor == 0L) "" else app.delta.string("albums_tag") ?: ""
            // 1) metadata delta
            while (!isStopped()) {
                val page = api.sync(cursor, albumsTag = albumsTag)
                val ups = page.items.filter { !it.deleted }.map {
                    RemoteMedia(
                        hash = it.hash, fileHash = it.fileHash, kind = it.kind, mime = it.mime,
                        takenAt = it.takenAt ?: 0, tz = it.tz, lat = it.lat, lon = it.lon, w = it.w, h = it.h,
                        duration = it.duration, bytes = it.bytes, name = it.name,
                        stores = it.stores.joinToString(","), pair = it.pair, description = it.desc, rev = it.rev, space = it.space, folder = it.folder, place = it.place, rot = it.rot,
                    )
                }
                val dels = page.items.filter { it.deleted }.map { it.hash }
                val links = page.items.filter { !it.deleted }.flatMap { i -> i.albums.map { AlbumMedia(it, i.hash) } }
                dao.applySync(ups, dels, links, page.albums?.map { AlbumRow(it.id, it.name, it.origin, it.shared) })
                page.albumsTag?.let { albumsTag = it; app.delta.setString("albums_tag", it) }
                files.removeFor(dels)
                // a changed video may have a new picture (e.g. a better frame than a black start): fetch it again
                files.removeFor(ups.filter { it.kind == "video" && files.hasThumb(it.hash) }.map { it.hash })
                changed += page.items.size
                cursor = page.cursor
                prefs.setCursor(cursor)
                progress("Synologyの写真の一覧を読み込み中 $changed 件")
                if (!page.hasMore) break
            }
            // ♡: the Synology keeps them (a new phone gets them back); this phone's older ones go up once
            try {
                val server = api.favorites().hashes.toSet()
                val local = prefs.favorites.first()
                val up = local - server
                if (up.isNotEmpty() && !app.delta.long("favorites_pushed").let { it > 0 })
                    api.setFavorites(com.photohub.app.data.api.FavoriteBody(up.toList(), true))
                app.delta.setLong("favorites_pushed", 1)
                prefs.setFavorites(server + if (app.delta.long("favorites_pushed") > 0) up else emptySet())
            } catch (_: Exception) {}   // older Synology
            // other files of the same photos (the phone's original in Drive vs. the Google Photos copy)
            // only the links made since the last look ("after" = the last answer's number)
            try {
                val after = app.delta.long("aliases_after")
                val r = api.aliases(after)
                val rows = r.items.filter { it.size == 2 }.map { com.photohub.app.data.db.RemoteAlias(it[0], it[1]) }
                if (after == 0L) dao.setAliases(rows) else if (rows.isNotEmpty()) dao.insertAliases(rows)
                app.delta.setLong("aliases_after", r.last)
            } catch (_: Exception) {}   // older Synology: no list
            if (listOnly) {   // mobile data without permission: the list only; thumbnails load when shown
                try { api.deviceSync("{}".toRequestBody()) } catch (_: Exception) {}
                return@withContext Result(changed, 0, 0, "更新しました")
            }
            // photos the Synology dated "the day they arrived": tell it the phone's real date
            try {
                // the Drive copies carry the phone's date: read the Drive list once if this phone has none yet
                if (dao.driveFiles().isEmpty()) app.drive.token()?.let { t -> app.drive.refresh(t) }
            } catch (_: Exception) {}
            try {
                // each correction once: "<file hash>@<date>" (a new date for the same photo is a new item)
                val sent = app.delta.sent("dates")
                val fixes = sent.notSent(dao.datesToFix()) { "${it.fileHash}@${it.takenAt}" }
                for (chunk in fixes.chunked(500)) {
                    api.fixDates(com.photohub.app.data.api.DateFixBody(chunk.map { com.photohub.app.data.api.DateFixItem(it.fileHash, it.takenAt) }))
                    sent.addAll(chunk.map { "${it.fileHash}@${it.takenAt}" })
                }
            } catch (_: Exception) {}
            // 2) thumbnails for everything, in packs of 300
            val missing = dao.allRemoteHashes().filterNot { files.hasThumb(it) }
            var thumbs = 0
            for (chunk in missing.chunked(300)) {
                if (isStopped()) break
                api.thumbs(HashesBody(chunk)).byteStream().use { s ->
                    ThumbPack.read(s) { h, data -> files.writeThumb(h, data); thumbs++ }
                }
                progress("縮小画像を読み込み中 $thumbs / ${missing.size}")
            }
            // 3) previews within the cap
            val s = prefs.current()
            val since = System.currentTimeMillis() - s.previewMonths * 30L * 24 * 3600 * 1000
            val favs = prefs.favorites.first()
            val wanted = LinkedHashSet<String>()
            dao.remoteByHashes(favs.toList()).forEach { wanted += it.hash }
            if (s.previewAlbums.isNotEmpty()) dao.remoteInAlbums(s.previewAlbums.toList()).forEach { wanted += it.hash }
            dao.remoteSince(since).filter { it.kind == "photo" }.forEach { wanted += it.hash }
            val cap = s.storageCapMb.toLong() * 1024 * 1024
            val base = app.net.baseUrl() ?: return@withContext Result(changed, thumbs, 0, "Synologyに接続できません")
            val client = app.net.client(s.token)
            var previews = 0
            var used = files.usedBytes()
            val metered = app.net.isMetered()
            for (h in wanted) {
                if (isStopped() || metered || light) break  // previews only on Wi-Fi, and not when the app just opened
                if (files.hasPreview(h)) continue
                if (used > cap) break
                val req = Request.Builder().url("$base/api/preview/$h").build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val bytes = r.body!!.bytes()
                        files.writePreview(h, bytes)
                        used += bytes.size
                        previews++
                    }
                }
                if (previews % 50 == 0) progress("すぐ開ける画像 $previews / ${wanted.size}")
            }
            files.evict(cap, wanted)
            try { api.deviceSync("{}".toRequestBody()) } catch (_: Exception) {}
            Result(changed, thumbs, previews, "更新しました")
        }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as PhotoHubApp
        if (!app.prefs.current().configured) return androidx.work.ListenableWorker.Result.success()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val light = inputData.getBoolean(KEY_LIGHT, false)   // the app was just opened: what is new, nothing heavy
        // periodic runs only at home (spec: 自宅のWi-Fiにいて充電中)
        if (!manual && !app.net.isHomeLan()) return androidx.work.ListenableWorker.Result.success()
        return try {
            app.indexer.scan()
            app.indexer.hashPending(limit = if (light) 100 else if (manual) 500 else 5000, isStopped = { isStopped })
            // one mobile-data setting for everything (send, receive, Google Drive)
            val limited = app.net.isMetered() && !app.prefs.current().backupOnMobile
            if (limited && !manual) return androidx.work.ListenableWorker.Result.success()
            val t0 = System.currentTimeMillis()
            val r = SyncEngine(app).run(progress = { setProgress(workDataOf("msg" to it)) }, isStopped = { isStopped }, listOnly = limited, light = light)
            AppLog.time(if (light) "同期（開いたとき）" else "同期", System.currentTimeMillis() - t0)
            if (r.message != "更新しました") AppLog.error("同期: ${r.message}")
            // the phone's record goes to the Synology (「動作の記録」)
            try { app.net.api()?.let { AppLog.upload(it, deviceName(app)) } } catch (_: Exception) {}
            if (r.message == "更新しました") androidx.work.ListenableWorker.Result.success(workDataOf("msg" to r.message))
            // a run started by hand (or on opening the app) does not sit in a growing retry wait:
            // the next opening / button press / periodic run tries again
            else if (manual) androidx.work.ListenableWorker.Result.failure(workDataOf("msg" to r.message))
            else androidx.work.ListenableWorker.Result.retry()
        } catch (e: Exception) {
            AppLog.error("同期", e)
            if (manual) androidx.work.ListenableWorker.Result.failure(workDataOf("msg" to "Synologyにつながりませんでした"))
            else androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
        const val KEY_LIGHT = "light"
        private const val PERIODIC = "photohub-sync"
        const val ONCE = "photohub-sync-now"

        fun schedule(ctx: Context) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)   // home Wi-Fi is checked in doWork (a VPN counts as metered)
                .setRequiresCharging(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(ctx: Context, light: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true, KEY_LIGHT to light))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // keep one that is running; replace one that is only waiting (e.g. a retry after a failure)
            val running = try {
                wm.getWorkInfosForUniqueWork(ONCE).get().any { it.state == androidx.work.WorkInfo.State.RUNNING }
            } catch (_: Exception) { false }
            wm.enqueueUniqueWork(ONCE, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Cheap periodic local re-index so new camera photos appear quickly (no network). */
class LocalIndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as PhotoHubApp
        app.indexer.scan()
        return androidx.work.ListenableWorker.Result.success()
    }
}

/**
 * Wakes up a few seconds after the camera (or any app) adds a photo or video, and starts a backup,
 * so new photos reach the Synology within minutes instead of at the next hourly run.
 * Android delivers this only once per request, so it puts itself back in place every time.
 * The backup itself keeps the usual rules (on/off, Wi-Fi only unless mobile data is allowed).
 */
class NewMediaWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as PhotoHubApp
        try {
            if (triggeredContentUris.isNotEmpty() || triggeredContentAuthorities.isNotEmpty()) {
                app.indexer.scan()
                val s = app.prefs.current()
                if (s.configured && (s.backupNas || s.backupDrive)) BackupWorker.runNow(applicationContext)
            }
        } catch (e: Exception) {
            AppLog.error("新しい写真の確認", e)
        } finally {
            watch(applicationContext, fromWorker = true)   // next new photo
        }
        return androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        private const val NAME = "photohub-new-media"

        /** fromWorker: called by the running watcher itself, so the next one waits for it to finish
         *  (replacing would cancel the very work that is running). */
        fun watch(ctx: Context, fromWorker: Boolean = false) {
            val c = Constraints.Builder()
                .addContentUriTrigger(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .addContentUriTrigger(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(15, TimeUnit.SECONDS)   // a burst of shots: wait until it settles
                .setTriggerContentMaxDelay(2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME,
                if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NewMediaWorker>().setConstraints(c).build())
        }
    }
}
