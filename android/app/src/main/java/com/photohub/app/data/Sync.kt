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

    suspend fun run(progress: suspend (String) -> Unit = {}, isStopped: () -> Boolean = { false }, listOnly: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            val api = app.net.api(force = true) ?: return@withContext Result(0, 0, 0, "Synologyに接続できません")
            // phone folders -> albums on the NAS first, so the delta below already carries them
            try { app.indexer.pushFolderAlbums(api) } catch (_: Exception) {}
            var cursor = prefs.current().cursor
            var changed = 0
            // 1) metadata delta
            while (!isStopped()) {
                val page = api.sync(cursor)
                val ups = page.items.filter { !it.deleted }.map {
                    RemoteMedia(
                        hash = it.hash, fileHash = it.fileHash, kind = it.kind, mime = it.mime,
                        takenAt = it.takenAt ?: 0, tz = it.tz, lat = it.lat, lon = it.lon, w = it.w, h = it.h,
                        duration = it.duration, bytes = it.bytes, name = it.name,
                        stores = it.stores.joinToString(","), pair = it.pair, description = it.desc, rev = it.rev, space = it.space, folder = it.folder, place = it.place,
                    )
                }
                val dels = page.items.filter { it.deleted }.map { it.hash }
                val links = page.items.filter { !it.deleted }.flatMap { i -> i.albums.map { AlbumMedia(it, i.hash) } }
                dao.applySync(ups, dels, links, page.albums.map { AlbumRow(it.id, it.name, it.origin, it.shared) })
                files.removeFor(dels)
                changed += page.items.size
                cursor = page.cursor
                prefs.setCursor(cursor)
                progress("Synologyの写真の一覧を読み込み中 $changed 件")
                if (!page.hasMore) break
            }
            if (listOnly) {   // mobile data without permission: the list only; thumbnails load when shown
                try { api.deviceSync("{}".toRequestBody()) } catch (_: Exception) {}
                return@withContext Result(changed, 0, 0, "更新しました")
            }
            // photos the Synology dated "the day they arrived": tell it the phone's real date
            try {
                val fixes = dao.datesToFix()
                for (chunk in fixes.chunked(500))
                    api.fixDates(com.photohub.app.data.api.DateFixBody(chunk.map { com.photohub.app.data.api.DateFixItem(it.fileHash, it.takenAt) }))
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
                if (isStopped() || metered) break  // previews only on Wi-Fi
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
        // periodic runs only at home (spec: 自宅のWi-Fiにいて充電中)
        if (!manual && !app.net.isHomeLan()) return androidx.work.ListenableWorker.Result.success()
        return try {
            app.indexer.scan()
            app.indexer.hashPending(limit = if (manual) 500 else 5000, isStopped = { isStopped })
            // one mobile-data setting for everything (send, receive, Google Drive)
            val limited = app.net.isMetered() && !app.prefs.current().backupOnMobile
            if (limited && !manual) return androidx.work.ListenableWorker.Result.success()
            val r = SyncEngine(app).run(progress = { setProgress(workDataOf("msg" to it)) }, isStopped = { isStopped }, listOnly = limited)
            if (r.message == "更新しました") androidx.work.ListenableWorker.Result.success(workDataOf("msg" to r.message))
            // a run started by hand (or on opening the app) does not sit in a growing retry wait:
            // the next opening / button press / periodic run tries again
            else if (manual) androidx.work.ListenableWorker.Result.failure(workDataOf("msg" to r.message))
            else androidx.work.ListenableWorker.Result.retry()
        } catch (e: Exception) {
            if (manual) androidx.work.ListenableWorker.Result.failure(workDataOf("msg" to "Synologyにつながりませんでした"))
            else androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val KEY_MANUAL = "manual"
        private const val PERIODIC = "photohub-sync"
        const val ONCE = "photohub-sync-now"

        fun schedule(ctx: Context) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(c)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
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
