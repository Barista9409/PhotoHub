package com.photohub.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.photohub.app.MainActivity
import com.photohub.app.PhotoHubApp
import com.photohub.app.R
import com.photohub.app.data.api.AppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app updates from the NAS (GET /api/app/latest, /api/app/download/{code}).
 *
 * Safety: an APK is installed only if its SHA-256 matches the NAS manifest, its package name is
 * ours, its version is newer, and it is signed with the same key as the installed app.
 * Android itself refuses an update signed with a different key as well.
 */
class Updater(private val app: PhotoHubApp) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: AppRelease) : State
        data class Downloading(val release: AppRelease, val percent: Int) : State
        data class Ready(val release: AppRelease, val file: File) : State
        data object Installing : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val currentVersionCode: Long get() = pkgInfo(app.packageName)?.longVersionCode ?: 0
    val currentVersionName: String get() = pkgInfo(app.packageName)?.versionName ?: "?"

    private fun pkgInfo(name: String): PackageInfo? = try {
        app.packageManager.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: PackageManager.NameNotFoundException) { null }

    /** Android asks once: "allow PhotoHub to install apps". */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        try {
            val api = app.net.api() ?: run { _state.value = State.Failed("NASに接続できません"); return@withContext null }
            val r = api.appLatest()
            if (r.versionCode > currentVersionCode) {
                // a file downloaded earlier and still valid?
                val f = apkFile(r)
                _state.value = if (f.exists() && sha256(f) == r.sha256) State.Ready(r, f) else State.Available(r)
                r
            } else {
                _state.value = State.UpToDate
                null
            }
        } catch (e: Exception) {
            _state.value = State.Failed("確認できません: ${e.message}")
            null
        }
    }

    private fun apkFile(r: AppRelease) = File(File(app.cacheDir, "updates").apply { mkdirs() }, "photohub-${r.versionCode}.apk")

    suspend fun download(r: AppRelease): File? = withContext(Dispatchers.IO) {
        try {
            val base = app.net.baseUrl() ?: throw IllegalStateException("NASに接続できません")
            val out = apkFile(r)
            File(out.parentFile, "").listFiles()?.filter { it != out }?.forEach { it.delete() }  // old downloads
            val tmp = File(out.path + ".part")
            app.net.client(app.prefs.current().token)
                .newCall(Request.Builder().url("$base/api/app/download/${r.versionCode}").build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: r.size
                    var done = 0L
                    tmp.outputStream().use { o ->
                        body.byteStream().use { i ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = i.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                done += n
                                _state.value = State.Downloading(r, if (total > 0) (done * 100 / total).toInt() else 0)
                            }
                        }
                    }
                }
            verify(tmp, r)?.let { tmp.delete(); throw IllegalStateException(it) }
            tmp.renameTo(out)
            _state.value = State.Ready(r, out)
            out
        } catch (e: Exception) {
            _state.value = State.Failed("ダウンロードできません: ${e.message}")
            null
        }
    }

    /** null = OK, else the reason the file must not be installed. */
    fun verify(file: File, r: AppRelease): String? {
        if (sha256(file) != r.sha256) return "ファイルが壊れています（ハッシュ不一致）"
        val pm = app.packageManager
        val info = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "APKとして読めません"
        if (info.packageName != app.packageName) return "別のアプリのAPKです"
        if (info.longVersionCode <= currentVersionCode) return "新しい版ではありません"
        val mine = signers(pkgInfo(app.packageName))
        val theirs = signers(info)
        if (mine.isEmpty() || mine != theirs) return "署名がインストール済みのアプリと違います（同じ鍵でビルドしてください）"
        return null
    }

    private fun signers(info: PackageInfo?): Set<String> {
        val si = info?.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        return sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    /** Hands the APK to Android's installer. Updates of ourselves can be silent on Android 12+. */
    fun install(file: File) {
        _state.value = State.Installing
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("photohub.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pi = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }

    fun onInstallFailed(message: String) { _state.value = State.Failed(message) }

    companion object {
        const val CHANNEL = "updates"
        const val NOTIFY_ID = 4201

        fun sha256(f: File): String = f.inputStream().use { LocalIndexer.sha256(it) }
        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun notify(ctx: Context, title: String, text: String, intent: Intent) {
            ensureChannel(ctx)
            val pi = PendingIntent.getActivity(ctx, 0, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_update).setContentTitle(title).setContentText(text)
                .setContentIntent(pi).setAutoCancel(true).build()
            try { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, n) } catch (_: SecurityException) {}
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("photohub-update", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** Background: check twice a day on Wi-Fi; download and install new versions automatically. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PhotoHubApp
        if (!app.prefs.current().configured || !app.prefs.current().autoUpdate) return Result.success()
        val u = app.updater
        val r = u.check() ?: return Result.success()
        val file = (u.state.value as? Updater.State.Ready)?.file ?: u.download(r) ?: return Result.retry()
        if (u.canInstall()) {
            u.install(file)
        } else {
            Updater.notify(app, "写真ハブ ${r.versionName} があります", "タップして更新してください",
                Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true))
        }
        return Result.success()
    }
}

/** Result of PackageInstaller. When Android needs the person to confirm, ask them. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as PhotoHubApp
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                // shown directly when the app is open; otherwise via a notification
                try { ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                Updater.notify(ctx, "写真ハブを更新します", "タップしてインストールを確認してください", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the app restarts on the new version
            else -> app.updater.onInstallFailed(
                "インストールできません: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "不明"))
        }
    }
}
