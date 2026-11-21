package com.photohub.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.photohub.app.PhotoHubApp
import com.photohub.app.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Sends Google Takeout ZIPs picked on the phone to the NAS (inbox/takeout/<me>/), in 8 MB
 * pieces so a broken connection resumes where it stopped (GET /api/takeout-upload).
 * Runs as a foreground job with a progress notification; Wi-Fi only.
 */
class TakeoutUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    @Serializable private data class Status(val received: Long = 0, val done: Boolean = false)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoHubApp
        val uris = inputData.getStringArray(KEY_URIS)?.map(Uri::parse) ?: return Result.success()
        setForeground(foreground(0, "準備中"))
        for ((n, uri) in uris.withIndex()) {
            val (name, total) = describe(uri) ?: continue
            val base = app.net.baseUrl(force = true) ?: return Result.retry()
            val client = app.net.client(app.token).newBuilder().readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(10, TimeUnit.MINUTES).build()
            val enc = URLEncoder.encode(name, "UTF-8")
            var offset = client.newCall(Request.Builder().url("$base/api/takeout-upload?name=$enc").build()).execute().use {
                if (!it.isSuccessful) return Result.retry()
                json.decodeFromString(Status.serializer(), it.body!!.string()).let { s -> if (s.done) total else s.received }
            }
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                var skipped = 0L
                while (skipped < offset) { val k = input.skip(offset - skipped); if (k <= 0) break; skipped += k }
                val buf = ByteArray(CHUNK)
                while (offset < total) {
                    if (isStopped) return Result.retry()
                    var len = 0
                    while (len < CHUNK) { val r = input.read(buf, len, CHUNK - len); if (r < 0) break; len += r }
                    if (len == 0) break
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = len.toLong()
                        override fun writeTo(sink: BufferedSink) { sink.write(buf, 0, len) }
                    }
                    val req = Request.Builder().url("$base/api/takeout-upload").post(body)
                        .header("X-File-Name", enc).header("X-Offset", offset.toString()).header("X-Total", total.toString()).build()
                    val ok = try { client.newCall(req).execute().use { it.isSuccessful } } catch (e: Exception) { false }
                    if (!ok) return Result.retry()  // resumes from the NAS's count next time
                    offset += len
                    val pct = (offset * 100 / total).toInt()
                    setProgress(workDataOf(KEY_NAME to name, KEY_PCT to pct, KEY_INDEX to n + 1, KEY_COUNT to uris.size))
                    setForeground(foreground(pct, "${n + 1}/${uris.size} $name"))
                }
            }
        }
        return Result.success(workDataOf(KEY_COUNT to uris.size))
    }

    private fun describe(uri: Uri): Pair<String, Long>? =
        applicationContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }

    private fun foreground(pct: Int, text: String): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Googleフォトの移行", NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle("TakeoutをNASへ送信中").setContentText(text).setOngoing(true)
            .setProgress(100, pct, pct == 0).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIFY_ID, n)
    }

    companion object {
        const val UNIQUE = "photohub-takeout-upload"
        const val KEY_URIS = "uris"; const val KEY_NAME = "name"; const val KEY_PCT = "pct"
        const val KEY_INDEX = "index"; const val KEY_COUNT = "count"
        private const val CHANNEL = "takeout"
        private const val NOTIFY_ID = 4301
        private const val CHUNK = 8 * 1024 * 1024

        fun start(ctx: Context, uris: List<Uri>) {
            uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            val req = OneTimeWorkRequestBuilder<TakeoutUploadWorker>()
                .setInputData(Data.Builder().putStringArray(KEY_URIS, uris.map { it.toString() }.toTypedArray()).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
