package com.photohub.app.data

import android.app.Activity
import android.content.Context
import android.net.Uri
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
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.HashesBody
import com.photohub.app.data.db.DriveFile
import com.photohub.app.data.db.LocalMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Google Drive as a rolling window of recent photos (spec v0.3):
 *  - only the scope drive.file: the app sees nothing in Drive except the files it uploaded
 *  - files go to the folder "PhotoHub" in the person's own Drive; Cloud Sync brings them to the NAS
 *  - files outside the window (months / GB) are deleted from Drive only after the NAS has them
 */
class DriveClient(private val app: PhotoHubApp) {
    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "PhotoHub"
    }

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    /** Interactive: returns null when connected, or an IntentSender-bearing result to launch. */
    suspend fun authorize(activity: Activity): AuthorizationResult =
        Identity.getAuthorizationClient(activity).authorize(request()).await()

    /** Silent (background): an access token, or null if the person has not connected Drive. */
    suspend fun token(): String? = try {
        val r = Identity.getAuthorizationClient(app).authorize(request()).await()
        if (r.hasResolution()) null else r.accessToken?.also { app.driveToken = it }
    } catch (e: Exception) { null }

    private fun call(token: String, req: Request.Builder): String =
        http.newCall(req.header("Authorization", "Bearer $token").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("Drive HTTP ${r.code}: ${body.take(200)}")
            body
        }

    suspend fun folderId(token: String): String = withContext(Dispatchers.IO) {
        app.prefs.driveFolder()?.let { return@withContext it }
        val q = URLEncoder.encode("name='$FOLDER' and mimeType='application/vnd.google-apps.folder' and trashed=false", "UTF-8")
        val found = json.parseToJsonElement(call(token, Request.Builder().url("$API/files?q=$q&fields=files(id)")))
            .let { (it as JsonObject)["files"] as kotlinx.serialization.json.JsonArray }
        val id = if (found.isNotEmpty()) (found[0] as JsonObject)["id"]!!.jsonPrimitive.content else {
            val meta = """{"name":"$FOLDER","mimeType":"application/vnd.google-apps.folder"}"""
            (json.parseToJsonElement(call(token, Request.Builder().url("$API/files?fields=id")
                .post(meta.toRequestBody("application/json".toMediaType())))) as JsonObject)["id"]!!.jsonPrimitive.content
        }
        app.prefs.setDriveFolder(id)
        id
    }

    @Serializable private data class GFile(val id: String, val name: String = "", val size: String? = null,
                                           val mimeType: String? = null, val appProperties: Map<String, String> = emptyMap())
    @Serializable private data class GList(val files: List<GFile> = emptyList(), val nextPageToken: String? = null)

    /** What is in our Drive folder (rebuilds the local table, e.g. after reinstalling). */
    suspend fun list(token: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val folder = folderId(token)
        val q = URLEncoder.encode("'$folder' in parents and trashed=false", "UTF-8")
        val out = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$API/files?q=$q&pageSize=1000&fields=nextPageToken,files(id,name,size,mimeType,appProperties)" +
                (page?.let { "&pageToken=$it" } ?: "")
            val l = json.decodeFromString<GList>(call(token, Request.Builder().url(url)))
            l.files.forEach { f ->
                val fh = f.appProperties["fileHash"] ?: return@forEach
                out += DriveFile(fh, f.id, f.name, f.size?.toLongOrNull() ?: 0, f.appProperties["takenAt"]?.toLongOrNull() ?: 0, f.mimeType)
            }
            page = l.nextPageToken
        } while (page != null)
        out
    }

    /** Resumable upload streamed from the phone's file. */
    suspend fun upload(token: String, m: LocalMedia): DriveFile = withContext(Dispatchers.IO) {
        val folder = folderId(token)
        val mime = m.mime ?: "application/octet-stream"
        val meta = """{"name":${Json.encodeToString(kotlinx.serialization.serializer<String>(), m.name)},"parents":["$folder"],""" +
            """"appProperties":{"fileHash":"${m.fileHash}","takenAt":"${m.takenAt}"}}"""
        val init = http.newCall(Request.Builder().url("$UPLOAD/files?uploadType=resumable&fields=id")
            .header("Authorization", "Bearer $token").header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", m.size.toString())
            .post(meta.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()).execute()
        val session = init.use { if (!it.isSuccessful) throw IllegalStateException("Drive HTTP ${it.code}"); it.header("Location")!! }
        val body = streamBody(app, Uri.parse(m.uri), mime, m.size)
        val res = call(token, Request.Builder().url(session).put(body))
        val id = (json.parseToJsonElement(res) as JsonObject)["id"]!!.jsonPrimitive.content
        DriveFile(m.fileHash!!, id, m.name, m.size, m.takenAt, m.mime)
    }

    suspend fun delete(token: String, id: String) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url("$API/files/$id").header("Authorization", "Bearer $token").delete().build())
            .execute().use { if (!it.isSuccessful && it.code != 404) throw IllegalStateException("Drive HTTP ${it.code}") }
    }

    /** URL for viewing a Drive copy (the image loader adds the token). */
    fun mediaUrl(driveId: String) = "$API/files/$driveId?alt=media"
}

fun streamBody(ctx: Context, uri: Uri, mime: String, size: Long): RequestBody = object : RequestBody() {
    override fun contentType() = mime.toMediaType()
    override fun contentLength() = size
    override fun writeTo(sink: BufferedSink) {
        ctx.contentResolver.openInputStream(uri)!!.source().use { sink.writeAll(it) }
    }
}

/** Which photos belong in the Drive window: newest first, within N months and/or N GB. */
object Window {
    fun keep(items: List<Pair<String, Pair<Long, Long>>>, s: Settings, now: Long = System.currentTimeMillis()): Set<String> {
        // items: fileHash -> (takenAt, size)
        val cutoff = now - s.driveMonths * 30L * 24 * 3600 * 1000
        val budget = s.driveGb.toLong() * 1024 * 1024 * 1024
        var used = 0L
        val out = LinkedHashSet<String>()
        for ((h, ts) in items.sortedByDescending { it.second.first }) {
            val (taken, size) = ts
            if (s.driveMode != "gb" && taken < cutoff) break
            if (s.driveMode != "months" && used + size > budget) break
            used += size
            out += h
        }
        return out
    }
}

class BackupEngine(private val app: PhotoHubApp) {
    data class Result(val toNas: Int, val toDrive: Int, val removedFromDrive: Int, val message: String)

    suspend fun run(progress: suspend (String) -> Unit = {}, isStopped: () -> Boolean = { false }): Result =
        withContext(Dispatchers.IO) {
            val s = app.prefs.current()
            val metered = app.net.isMetered()
            if (metered && !s.backupOnMobile) return@withContext Result(0, 0, 0, "モバイル回線のため待機中")
            app.indexer.scan()
            app.indexer.hashPending(limit = 3000) { isStopped() }
            val local = app.db.media().localHashed()
            val api = if (s.configured) app.net.api() else null
            // --- 1. straight to the NAS ---
            var toNas = 0
            val onNas = HashSet<String>(app.db.media().remoteFileHashes())
            if (api != null) {
                val unknown = local.filter { it.fileHash !in onNas }
                for (chunk in unknown.chunked(500)) {
                    val have = try { api.have(HashesBody(chunk.map { it.fileHash!! })) } catch (e: Exception) { emptyMap() }
                    have.filterValues { it == "nas" }.keys.forEach { onNas += it }
                    if (!s.backupNas) continue
                    for (m in chunk) {
                        if (isStopped()) break
                        if (have[m.fileHash] != null) continue
                        if (uploadToNas(m)) { toNas++; progress("NASへ $toNas 件") }
                    }
                }
            }
            // --- 2. Google Drive window ---
            var toDrive = 0
            var removed = 0
            if (s.backupDrive) {
                val drive = app.drive
                val token = drive.token() ?: return@withContext Result(toNas, 0, 0, "Googleドライブに再接続してください")
                val inDrive = drive.list(token).also { app.db.media().clearDrive(); app.db.media().upsertDrive(it) }
                    .associateBy { it.fileHash }.toMutableMap()
                val candidates = (local.map { it.fileHash!! to (it.takenAt to it.size) } +
                    inDrive.values.map { it.fileHash to (it.takenAt to it.size) }).distinctBy { it.first }
                val keep = Window.keep(candidates, s)
                for (m in local) {
                    if (isStopped()) break
                    val h = m.fileHash!!
                    if (h in keep && h !in inDrive) {
                        try {
                            val f = drive.upload(token, m)
                            inDrive[h] = f
                            app.db.media().upsertDrive(listOf(f))
                            toDrive++
                            progress("Googleドライブへ $toDrive 件")
                        } catch (e: Exception) { /* next run */ }
                    }
                }
                // tidy: outside the window AND safely on the NAS
                val outside = inDrive.values.filter { it.fileHash !in keep }
                if (outside.isNotEmpty() && api != null) {
                    for (chunk in outside.chunked(500)) {
                        val have = try { api.have(HashesBody(chunk.map { it.fileHash })) } catch (e: Exception) { emptyMap() }
                        for (f in chunk) {
                            if (have[f.fileHash] != "nas") continue
                            try { drive.delete(token, f.driveId); app.db.media().deleteDrive(listOf(f.fileHash)); removed++ }
                            catch (e: Exception) { }
                        }
                    }
                }
            }
            Result(toNas, toDrive, removed, "バックアップ完了")
        }

    private fun deviceName(): String =
        android.provider.Settings.Global.getString(app.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL

    private fun uploadToNas(m: LocalMedia): Boolean {
        val base = kotlinx.coroutines.runBlocking { app.net.baseUrl() } ?: return false
        val req = Request.Builder().url("$base/api/upload")
            .header("X-File-Hash", m.fileHash!!).header("X-File-Name", URLEncoder.encode(m.name, "UTF-8"))
            // the NAS puts it in Synology Photos' own backup folder for this phone, same layout
            .header("X-Device", URLEncoder.encode(deviceName(), "UTF-8"))
            .header("X-Rel-Path", URLEncoder.encode(m.relPath ?: "", "UTF-8"))
            .post(streamBody(app, Uri.parse(m.uri), m.mime ?: "application/octet-stream", m.size)).build()
        return try {
            app.net.client(app.token).newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES)
                .build().newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as PhotoHubApp
        val s = app.prefs.current()
        if (!s.backupNas && !s.backupDrive) return androidx.work.ListenableWorker.Result.success()
        return try {
            val r = BackupEngine(app).run(progress = { setProgress(workDataOf("msg" to it)) }) { isStopped }
            app.prefs.setLastBackup(System.currentTimeMillis(), "${r.message}（NAS ${r.toNas}・ドライブ ${r.toDrive}・整理 ${r.removedFromDrive}）")
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            app.prefs.setLastBackup(System.currentTimeMillis(), "失敗: ${e.message?.take(120)}")
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val ONCE = "photohub-backup-now"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("photohub-backup", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, req)
        }
    }
}
