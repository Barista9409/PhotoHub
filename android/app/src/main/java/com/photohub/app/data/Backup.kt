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
import kotlinx.coroutines.async
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

    /** A new token after the old one expired (the cached one is cleared first). */
    suspend fun refreshToken(): String? {
        val old = app.driveToken
        if (old.isNotEmpty()) try {
            com.google.android.gms.auth.api.identity.Identity.getAuthorizationClient(app)
                .clearToken(com.google.android.gms.auth.api.identity.ClearTokenRequest.builder().setToken(old).build()).await()
        } catch (_: Exception) {}
        return token()
    }

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
    @Serializable private data class GChangeFile(val id: String, val name: String = "", val size: String? = null, val mimeType: String? = null,
                                                 val appProperties: Map<String, String> = emptyMap(), val parents: List<String> = emptyList(),
                                                 val trashed: Boolean = false)
    @Serializable private data class GChange(val fileId: String? = null, val removed: Boolean = false, val file: GChangeFile? = null)
    @Serializable private data class GChanges(val changes: List<GChange> = emptyList(), val nextPageToken: String? = null,
                                              val newStartPageToken: String? = null)
    @Serializable private data class GStart(val startPageToken: String)

    /**
     * Bring the phone's copy of the Drive folder list up to date with only what changed since last
     * time (Drive's change list: a token marks "seen up to here"; additions, edits, deletions and
     * trashing all come through it, so nothing is missed). The first time, or when the token is no
     * longer accepted, the whole folder is read once and a fresh token taken *before* reading, so
     * changes made during the read are fetched next time.
     */
    suspend fun refresh(token: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val dao = app.db.media()
        val folder = folderId(token)
        var page = app.delta.string("drive_changes")
        if (page != null) {
            try {
                val gone = mutableListOf<String>(); val upd = mutableListOf<DriveFile>()
                while (page != null) {
                    val url = "$API/changes?pageToken=$page&pageSize=1000&spaces=drive&includeRemoved=true" +
                        "&fields=nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,size,mimeType,appProperties,parents,trashed))"
                    val c = json.decodeFromString<GChanges>(call(token, Request.Builder().url(url)))
                    for (ch in c.changes) {
                        val f = ch.file
                        val fh = f?.appProperties?.get("fileHash")
                        if (ch.removed || f == null || f.trashed || folder !in f.parents || fh == null) { ch.fileId?.let { gone += it } }
                        else upd += DriveFile(fh, f.id, f.name, f.size?.toLongOrNull() ?: 0, f.appProperties["takenAt"]?.toLongOrNull() ?: 0, f.mimeType)
                    }
                    if (c.newStartPageToken != null) { app.delta.setString("drive_changes", c.newStartPageToken); page = null }
                    else page = c.nextPageToken
                }
                if (gone.isNotEmpty()) dao.deleteDriveIds(gone)
                if (upd.isNotEmpty()) dao.upsertDrive(upd)
                return@withContext dao.driveFiles()
            } catch (e: Exception) {
                AppLog.error("ドライブの変更一覧", e)   // token too old or not accepted: read everything once
            }
        }
        val start = json.decodeFromString<GStart>(call(token, Request.Builder().url("$API/changes/startPageToken"))).startPageToken
        val all = list(token)
        dao.clearDrive(); dao.upsertDrive(all)
        app.delta.setString("drive_changes", start)
        all
    }

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
            """"modifiedTime":"${java.time.Instant.ofEpochMilli(m.takenAt)}",""" +
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

/** Where one part of a backup run is, for the progress bars and the notification. */
data class BackupStep(val phase: String, val label: String, val done: Int, val total: Int, val finished: Boolean = false) {
    val fraction get() = if (total > 0) done.toFloat() / total else 0f
    companion object {
        const val PREPARE = "prepare"; const val NAS = "nas"; const val DRIVE = "drive"
        val ORDER = listOf(PREPARE, NAS, DRIVE)
    }
}

class BackupEngine(private val app: PhotoHubApp) {
    companion object {
        /** Set by the "止める" button; checked before every file. Turning a backup off also stops it. */
        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    data class Result(val toNas: Int, val toDrive: Int, val removedFromDrive: Int, val message: String,
                      val checked: Int = 0, val alreadyOnNas: Int = 0, val failed: Int = 0)

    /** Phone photos are checked once, then the Synology and Google Drive parts run at the same time. */
    suspend fun run(progress: suspend (BackupStep) -> Unit = {}, isStopped: () -> Boolean = { false }): Result =
        withContext(Dispatchers.IO) {
            stopRequested.set(false)
            val s = app.prefs.current()
            val metered = app.net.isMetered()
            if (metered && !s.backupOnMobile) return@withContext Result(0, 0, 0, "モバイル回線のため待機中")
            app.indexer.scan()
            // first time: every photo is checked once (fingerprint + place); later only new ones
            val unhashed = app.db.media().countUnhashed()
            if (unhashed > 0) {
                progress(BackupStep(BackupStep.PREPARE, "スマホの写真を確認中", 0, unhashed))
                app.indexer.hashPending(limit = 1_000_000, isStopped = { isStopped() || stopRequested.get() }) { d, t ->
                    progress(BackupStep(BackupStep.PREPARE, "スマホの写真を確認中", d, t))
                }
                progress(BackupStep(BackupStep.PREPARE, "スマホの写真を確認中", unhashed, unhashed, finished = true))
            }
            // only the folders chosen to be sent (and photos chosen by hand from the others)
            val local = BackupChoice.filter(app, app.db.media().localHashed())
            val api = if (s.configured) app.net.api() else null
            val nasPart = async { nasPart(local, api, s.backupNas, progress, isStopped) }
            val drivePart = async { drivePart(local, api, s.backupDrive, progress, isStopped) }
            val n = nasPart.await()
            val d = drivePart.await()
            val stopped = stopRequested.getAndSet(false)
            val message = when {
                stopped -> "途中で止めました"
                d.error != null -> d.error
                n.unreachable -> "Synologyにつながらないため送れませんでした"
                n.failed > 0 -> "一部送れませんでした"
                else -> "完了"
            }
            Result(n.sent, d.sent, d.removed, message, local.size, n.already, n.failed)
        }

    private data class NasOut(val sent: Int = 0, val already: Int = 0, val failed: Int = 0, val unreachable: Boolean = false)
    private data class DriveOut(val sent: Int = 0, val removed: Int = 0, val error: String? = null)

    private suspend fun nasPart(local: List<LocalMedia>, api: com.photohub.app.data.api.PhotoHubApi?, on: Boolean,
                                progress: suspend (BackupStep) -> Unit, isStopped: () -> Boolean): NasOut {
        if (api == null) return NasOut(unreachable = on)
        val label = "Synologyへ送信中"
        if (on) progress(BackupStep(BackupStep.NAS, "Synologyにある写真を確認中", 0, 0))
        val onNas = HashSet<String>(app.db.media().remoteFileHashes())
        // photos deleted on the Synology on purpose: asked once, never again
        val deletedThere = app.delta.sent("deleted-on-synology")
        val unknown = local.filter { it.fileHash !in onNas && it.fileHash!! !in deletedThere }
        // ask the Synology first, so the bar knows the real number to send
        val toSend = mutableListOf<LocalMedia>()
        for (chunk in unknown.chunked(500)) {
            val have = try { api.have(HashesBody(chunk.map { it.fileHash!! })) } catch (e: Exception) { emptyMap() }
            // "queued" = it already received it and is filing it away
            val there = have.filterValues { it == "nas" || it == "queued" }.keys.toList()
            if (there.isNotEmpty()) app.db.media().markOnNas(there)   // ("deleted" = deleted on purpose: not sent again)
            deletedThere.addAll(have.filterValues { it == "deleted" }.keys)
            toSend += chunk.filter { have[it.fileHash] == null }
        }
        val already = local.size - toSend.size
        if (!on) return NasOut(already = already)
        var sent = 0; var failed = 0
        for ((i, m) in toSend.withIndex()) {
            if (isStopped() || stopRequested.get() || !app.prefs.current().backupNas) break
            progress(BackupStep(BackupStep.NAS, label, i, toSend.size))
            if (uploadToNas(m)) { sent++; app.db.media().markOnNas(listOf(m.fileHash!!)) } else failed++
        }
        progress(BackupStep(BackupStep.NAS, label, toSend.size, toSend.size, finished = true))
        return NasOut(sent, already, failed)
    }

    /** Google Drive window: the recent ones go to Drive whether or not the Synology has them too. */
    private suspend fun drivePart(local: List<LocalMedia>, api: com.photohub.app.data.api.PhotoHubApi?, on: Boolean,
                                  progress: suspend (BackupStep) -> Unit, isStopped: () -> Boolean): DriveOut {
        if (!on || stopRequested.get()) return DriveOut()
        val s = app.prefs.current()
        val drive = app.drive
        progress(BackupStep(BackupStep.DRIVE, "Googleドライブの中身を確認中", 0, 0))
        val token = drive.token()
        if (token == null) {
            progress(BackupStep(BackupStep.DRIVE, "", 0, 0, finished = true))
            return DriveOut(error = "Googleドライブに再接続してください")
        }
        val inDrive = drive.refresh(token).associateBy { it.fileHash }.toMutableMap()
        val candidates = (local.map { it.fileHash!! to (it.takenAt to it.size) } +
            inDrive.values.map { it.fileHash to (it.takenAt to it.size) }).distinctBy { it.first }
        val keep = Window.keep(candidates, s)
        val toUpload = local.filter { it.fileHash!! in keep && it.fileHash !in inDrive }
        var sent = 0
        val label = "Googleドライブへ送信中"
        for ((i, m) in toUpload.withIndex()) {
            if (isStopped() || stopRequested.get() || !app.prefs.current().backupDrive) break
            progress(BackupStep(BackupStep.DRIVE, label, i, toUpload.size))
            try {
                val f = drive.upload(token, m)
                inDrive[m.fileHash!!] = f
                app.db.media().upsertDrive(listOf(f))
                sent++
            } catch (e: Exception) { AppLog.error("ドライブへの保存", e) /* next run */ }
        }
        // tidy: outside the window AND safely on the Synology
        var removed = 0
        val outside = inDrive.values.filter { it.fileHash !in keep }
        if (outside.isNotEmpty() && api != null && !stopRequested.get() && app.prefs.current().backupDrive) {
            progress(BackupStep(BackupStep.DRIVE, "Googleドライブの古い写真を整理中", 0, outside.size))
            var done = 0
            val gone = mutableListOf<String>()
            for (chunk in outside.chunked(500)) {
                val have = try { api.have(HashesBody(chunk.map { it.fileHash })) } catch (e: Exception) { emptyMap() }
                for (f in chunk) {
                    done++
                    if (have[f.fileHash] != "nas") continue
                    try { drive.delete(token, f.driveId); app.db.media().deleteDrive(listOf(f.fileHash)); removed++; gone += f.fileHash }
                    catch (e: Exception) { }
                }
                progress(BackupStep(BackupStep.DRIVE, "Googleドライブの古い写真を整理中", done, outside.size))
            }
            // the Synology keeps a copy of the Drive folder (Cloud Sync): let it delete those too
            for (chunk in gone.chunked(1000)) try { api.driveRemoved(HashesBody(chunk)) } catch (_: Exception) {}
        }
        progress(BackupStep(BackupStep.DRIVE, label, toUpload.size, toUpload.size, finished = true))
        return DriveOut(sent, removed)
    }

    private fun deviceName(): String = deviceName(app)

    private fun uploadToNas(m: LocalMedia): Boolean {
        val base = kotlinx.coroutines.runBlocking { app.net.baseUrl() } ?: return false
        val req = Request.Builder().url("$base/api/upload")
            .header("X-File-Hash", m.fileHash!!).header("X-File-Name", URLEncoder.encode(m.name, "UTF-8"))
            // the NAS puts it in Synology Photos' own backup folder for this phone, same layout
            .header("X-Device", URLEncoder.encode(deviceName(), "UTF-8"))
            .header("X-Rel-Path", URLEncoder.encode(m.relPath ?: "", "UTF-8"))
            // the phone's date: files without a date inside (screenshots, PNG ...) keep it on the Synology
            .header("X-Taken-At", m.takenAt.toString())
            .post(streamBody(app, Uri.parse(m.uri), m.mime ?: "application/octet-stream", m.size)).build()
        return try {
            app.net.client(app.token).newBuilder().readTimeout(10, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.MINUTES)
                .build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) AppLog.error("Synologyへの送信: HTTP ${r.code}")
                    r.isSuccessful
                }
        } catch (e: Exception) { AppLog.error("Synologyへの送信", e); false }
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val app = applicationContext as PhotoHubApp
        val s = app.prefs.current()
        if (!s.backupNas && !s.backupDrive) return androidx.work.ListenableWorker.Result.success()
        return try {
            var lastNotify = 0L
            val running = java.util.concurrent.ConcurrentHashMap<String, BackupStep>()
            val r = BackupEngine(app).run(progress = { st ->
                if (st.finished) running.remove(st.phase) else running[st.phase] = st
                val active = BackupStep.ORDER.mapNotNull { running[it] }
                val data = mutableMapOf<String, Any?>("active" to active.joinToString(",") { it.phase },
                    "msg" to active.joinToString("・") { "${it.label} ${it.done}/${it.total}" })
                active.forEach { data["${it.phase}_label"] = it.label; data["${it.phase}_done"] = it.done; data["${it.phase}_total"] = it.total }
                setProgress(workDataOf(*data.map { it.key to it.value }.toTypedArray()))
                val now = System.currentTimeMillis()
                if (active.isNotEmpty() && (now - lastNotify > 1500)) {  // a progress notification while it runs
                    lastNotify = now
                    try { setForeground(foregroundInfo(applicationContext, active)) } catch (_: Exception) {}
                }
            }) { isStopped }
            val parts = buildList {
                add("スマホの写真 ${r.checked} 枚")
                if (r.alreadyOnNas > 0) add("Synologyに保存済み ${r.alreadyOnNas} 枚")
                if (s.backupNas) add("今回Synologyへ ${r.toNas} 枚")
                if (r.failed > 0) add("送れなかった ${r.failed} 枚（次回再送）")
                if (s.backupDrive) add("ドライブへ ${r.toDrive} 枚・整理 ${r.removedFromDrive} 枚")
            }
            app.prefs.setLastBackup(System.currentTimeMillis(), "${r.message}：" + parts.joinToString("・"))
            SyncWorker.runNow(applicationContext)  // marks (NAS / Drive) refresh
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            AppLog.error("バックアップ", e)
            app.prefs.setLastBackup(System.currentTimeMillis(), "失敗: ${e.message?.take(120)}")
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val ONCE = "photohub-backup-now"
        const val PERIODIC = "photohub-backup"

        fun foregroundInfo(ctx: Context, active: List<BackupStep>): androidx.work.ForegroundInfo {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            nm.createNotificationChannel(android.app.NotificationChannel("backup", "同期と保存", android.app.NotificationManager.IMPORTANCE_LOW))
            val main = active.first()
            val pct = (main.fraction * 100).toInt()
            val text = active.joinToString("　") { st ->
                val name = when (st.phase) { BackupStep.NAS -> "Synology"; BackupStep.DRIVE -> "ドライブ"; else -> "確認" }
                if (st.total > 0) "$name ${st.done}/${st.total}" else "$name 準備中"
            }
            val n = androidx.core.app.NotificationCompat.Builder(ctx, "backup").setSmallIcon(com.photohub.app.R.drawable.ic_stat_update)
                .setContentTitle(if (active.size > 1) "同期・保存中" else main.label).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(100, pct, main.total == 0).build()
            return if (android.os.Build.VERSION.SDK_INT >= 29)
                androidx.work.ForegroundInfo(4401, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else androidx.work.ForegroundInfo(4401, n)
        }
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun runNow(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val req = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // keep one that is running; replace one that is only waiting for a retry
            val running = try {
                wm.getWorkInfosForUniqueWork(ONCE).get().any { it.state == androidx.work.WorkInfo.State.RUNNING }
            } catch (_: Exception) { false }
            wm.enqueueUniqueWork(ONCE, if (running) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** The phone's name as its owner set it (falls back to the model). */
fun deviceName(ctx: Context): String =
    android.provider.Settings.Global.getString(ctx.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
