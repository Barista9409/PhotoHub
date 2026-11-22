package com.photohub.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Save many photos/videos (a whole album, or a selection) from the Synology to this phone, into
 * Pictures/PhotoHub/<name>. Runs in the background with a progress notification; photos that are
 * already on the phone are skipped.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoHubApp
        val list = File(inputData.getString(KEY_LIST) ?: return Result.failure())
        val folder = (inputData.getString(KEY_FOLDER) ?: "PhotoHub").replace(Regex("""[\\/:*?"<>|]"""), "_").take(60)
        val dest = inputData.getString(KEY_DEST) ?: "pictures"
        val hashes = list.readLines().filter { it.isNotBlank() }
        val base = app.net.baseUrl(force = true) ?: return Result.retry()
        val client = app.net.client(app.prefs.current().token).newBuilder().readTimeout(10, TimeUnit.MINUTES).build()
        var done = 0; var saved = 0; var failed = 0
        for (h in hashes) {
            if (isStopped) break
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to hashes.size))
            try { setForeground(info(applicationContext, folder, done, hashes.size)) } catch (_: Exception) {}
            done++
            val m = app.db.media().remote(h) ?: continue
            val req = Request.Builder().url("$base/api/original/$h").build()
            val ok = try {
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use false
                    val video = m.kind == "video"
                    val mime = m.mime ?: if (video) "video/mp4" else "image/jpeg"
                    val out = openOut(applicationContext, dest, folder, m.name, mime, video, m.takenAt) ?: return@use false
                    out.second.use { o -> r.body!!.byteStream().copyTo(o) }
                    out.first()
                    true
                }
            } catch (e: Exception) { false }
            if (ok) saved++ else failed++
        }
        list.delete()
        app.indexer.scan()
        return Result.success(workDataOf("msg" to "${saved} 件を保存しました" + if (failed > 0) "（${failed} 件は失敗）" else ""))
    }

    companion object {
        const val KEY_LIST = "list"; const val KEY_FOLDER = "folder"; const val KEY_DONE = "done"; const val KEY_TOTAL = "total"
        const val KEY_DEST = "dest"

        /** Where downloads go: "pictures" / "dcim" / "download" (all under …/PhotoHub/<name>) or "tree:<uri>" (a chosen folder). */
        fun destLabel(dest: String, folder: String): String = when {
            dest == "dcim" -> "DCIM/PhotoHub/$folder"
            dest == "download" -> "Download/PhotoHub/$folder"
            dest.startsWith("tree:") -> (android.net.Uri.parse(dest.removePrefix("tree:")).lastPathSegment?.substringAfter(':') ?: "選んだフォルダ") + "/$folder"
            else -> "Pictures/PhotoHub/$folder"
        }

        /** An output stream for one file at the destination, plus a function to call when it is written. */
        fun openOut(ctx: Context, dest: String, folder: String, name: String, mime: String, video: Boolean,
                    takenAt: Long): Pair<() -> Unit, java.io.OutputStream>? {
            val cr = ctx.contentResolver
            if (dest.startsWith("tree:")) {
                val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(dest.removePrefix("tree:"))) ?: return null
                val dir = root.findFile(folder)?.takeIf { it.isDirectory } ?: root.createDirectory(folder) ?: return null
                val f = dir.createFile(mime, name) ?: return null
                return ({}) to (cr.openOutputStream(f.uri) ?: return null)
            }
            val base = when (dest) { "dcim" -> "DCIM"; "download" -> "Download"; else -> "Pictures" }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/PhotoHub/$folder")
                if (takenAt > 0 && dest != "download") put(MediaStore.MediaColumns.DATE_TAKEN, takenAt)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = when {
                dest == "download" -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val uri = cr.insert(coll, values) ?: return null
            val out = cr.openOutputStream(uri) ?: return null
            return ({ cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null); Unit }) to out
        }
        const val UNIQUE = "photohub-download"

        /** Queue a download of these items (only ones on the Synology and not already on this phone). */
        fun start(ctx: Context, items: List<TimelineItem>, folder: String, dest: String = "pictures"): Int {
            val want = items.filter { it.hash != null && it.localUri == null }.mapNotNull { it.hash }
            if (want.isEmpty()) return 0
            val f = File(ctx.cacheDir, "download-${System.currentTimeMillis()}.txt").apply { writeText(want.joinToString("\n")) }
            val app = ctx.applicationContext as PhotoHubApp
            val mobileOk = kotlinx.coroutines.runBlocking { app.prefs.current().backupOnMobile }
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_LIST to f.absolutePath, KEY_FOLDER to folder, KEY_DEST to dest))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (mobileOk) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            return want.size
        }

        fun info(ctx: Context, folder: String, done: Int, total: Int): ForegroundInfo {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("download", "ダウンロード", android.app.NotificationManager.IMPORTANCE_LOW))
            val n = androidx.core.app.NotificationCompat.Builder(ctx, "download").setSmallIcon(com.photohub.app.R.drawable.ic_stat_update)
                .setContentTitle("「$folder」を保存中").setContentText("$done / $total")
                .setOngoing(true).setOnlyAlertOnce(true).setProgress(total, done, false).build()
            return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4402, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                   else ForegroundInfo(4402, n)
        }
    }
}
