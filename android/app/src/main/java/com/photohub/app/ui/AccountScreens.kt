package com.photohub.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photohub.app.BuildConfig
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.BackupWorker
import com.photohub.app.data.Settings
import com.photohub.app.data.SyncWorker
import com.photohub.app.data.Updater
import com.photohub.app.data.api.Me
import com.photohub.app.data.api.MySettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import com.photohub.app.data.api.Status

/** Link that always downloads the newest app (a fixed-name copy attached to every release). */
val APP_DOWNLOAD_URL = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/PhotoHub.apk"

/** Where the account hub's rows lead. */
enum class Dest(val route: String, val title: String) {
    NAS_BACKUP("backup_nas", "Synologyと同期"),
    DRIVE_BACKUP("backup_drive", "最近の写真と動画をGoogleドライブに保存"),
    SIMILAR("similar", "似ている写真"),
    CLEANUP("cleanup", "Googleフォトから消してよい写真"),
    MIGRATION("migration", "Googleフォトからの移行"),
    IMPORT_LOG("import_log", "Synologyへの取り込み記録"),
    DEVICE("device", "このスマホ"),
    APP("app", "アプリ"),
    LIBRARY("library", "ライブラリの内訳"),
}

@Composable
private fun Chevron() = Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * The one place for everything that is not looking at photos (opened from the avatar in the top bar).
 * Groups follow what people want to do: back up → tidy up → move from Google Photos → this phone → app → account.
 */
@Composable
fun AccountHub(open: (Dest) -> Unit, onLoggedOut: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    val base by UiState.base.collectAsState()
    var me by remember { mutableStateOf<Me?>(null) }
    var status by remember { mutableStateOf<Status?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(base) {
        me = try { app.net.api()?.me() } catch (e: Exception) { null }
        status = try { app.net.api()?.status() } catch (e: Exception) { null }
    }
    val last by app.prefs.lastBackup.collectAsState(initial = 0L to "")
    val updater by app.updater.state.collectAsState()
    val live = rememberBackupLive()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- who / where ----
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text((me?.name ?: "?").take(1).uppercase(), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.let { "${it.name} さん" } ?: "PhotoHub", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(status?.let { "Synologyの写真 ${it.photos} 枚・動画 ${it.videos} 本" } ?: "Synologyの情報を読み込み中…",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                when {
                    base == null -> StatusPill("Synologyにつながりません", false)
                    base == cur.lanUrl -> StatusPill("自宅でつながっています", true)
                    else -> StatusPill("外出先からつながっています", true)
                }
                if (status?.apply == false) { Spacer(Modifier.size(8.dp)); StatusPill("Synologyは確認モード", false) }
            }
        }

        if (me?.photoFolder == false) {
            // created under an e-mail address by an older version: a new login finds the real user name
            Column(Modifier.padding(16.dp, 8.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Synology Photosの写真フォルダが見つかりません", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text("このため、Synology Photosに保存済みの写真が表示されていません。いったんログアウトして、もう一度ログインしてください（メールアドレスでもユーザー名でもかまいません）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                androidx.compose.material3.Button({ confirmLogout = true }) { Text("ログアウトしてログインし直す") }
            }
        }
        SectionTitle("同期と保存")
        Group {
            if (live.running) { BackupProgress(live); GroupDivider() }
            SettingRow(Icons.Default.PieChart, "ライブラリの内訳", librarySummary(),
                onClick = { open(Dest.LIBRARY) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.Dns, "Synologyと同期",
                if (cur.backupNas) "オン・" + (if (last.first > 0) "最後 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last.first))}" else "まだ実行していません") else "オフ",
                onClick = { open(Dest.NAS_BACKUP) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.Cloud, "最近の写真と動画をGoogleドライブに保存",
                if (cur.backupDrive) "オン・" + when (cur.driveMode) { "gb" -> "最大 ${cur.driveGb} GB"; "both" -> "直近 ${cur.driveMonths} か月・最大 ${cur.driveGb} GB"; else -> "直近 ${cur.driveMonths} か月" } else "オフ",
                onClick = { open(Dest.DRIVE_BACKUP) }) { Chevron() }
        }

        SectionTitle("写真の整理")
        Group {
            SettingRow(Icons.Default.AutoAwesomeMosaic, "似ている写真",
                status?.let { if (it.similarOpen > 0) "${it.similarOpen} 組。残す写真を選べます" else "ありません" } ?: "連写や加工前後の写真を見つけます",
                onClick = { open(Dest.SIMILAR) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.CleaningServices, "Googleフォトから消してよい写真",
                "Synologyに保存済みの古い写真を、月ごとに案内します", onClick = { open(Dest.CLEANUP) }) { Chevron() }
            GroupDivider()
            FreeUpSpaceRow()
        }

        SectionTitle("Googleフォトからの移行")
        Group {
            SettingRow(Icons.Default.PhotoLibrary, "TakeoutのZIPを送る", "Googleフォトの写真をSynologyへ移します",
                onClick = { open(Dest.MIGRATION) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Filled.ListAlt, "Synologyへの取り込み記録", "Takeout・スマホ・ドライブから入った件数と結果",
                onClick = { open(Dest.IMPORT_LOG) }) { Chevron() }
        }

        SectionTitle("このスマホ")
        Group {
            SettingRow(Icons.Default.PhoneAndroid, "このスマホに持つデータ", "一覧の更新・使う容量・外出先ですぐ開ける期間",
                onClick = { open(Dest.DEVICE) }) { Chevron() }
        }

        SectionTitle("アプリ")
        Group {
            SettingRow(Icons.Default.SystemUpdate, "更新と共有",
                when (updater) { is Updater.State.Available, is Updater.State.Ready -> "新しい版があります"; else -> "PhotoHub ${BuildConfig.VERSION_NAME}・家族に送る" },
                onClick = { open(Dest.APP) }) { Chevron() }
        }

        SectionTitle("アカウント")
        Group {
            PlaceSetting()
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Filled.Logout, "ログアウト", "別の家族がこのスマホを使うとき", onClick = { confirmLogout = true })
        }
    }

    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text("ログアウトしますか？") },
            text = { Text("このスマホに持っている一覧と縮小画像を消します。Synologyやドライブの写真はそのままです。") },
            confirmButton = { TextButton({
                confirmLogout = false
                scope.launch { app.switchUser(); app.prefs.save(app.prefs.current().copy(token = "")); UiState.refreshBase(app, true); onLoggedOut() }
            }) { Text("ログアウト") } },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("やめる") } })
    }
}

/** NAS backup: on/off, mobile data, run now. */
@Composable
fun NasBackupScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    fun save(n: Settings, run: Boolean = false) = scope.launch { app.prefs.save(n); if (run) BackupWorker.runNow(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("このスマホとSynologyの写真をそろえます。スマホで撮った写真はSynologyへ送り（Synology Photosと同じフォルダなので重複しません）、Synologyにある写真は一覧と縮小画像を受け取って、このアプリで見られるようにします。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        SectionTitle("送る（スマホ → Synology）")
        Group {
            SwitchRow(Icons.Default.Dns, "撮った写真と動画をSynologyへ送る", if (cur.backupNas) "オン" else "オフ", cur.backupNas) { save(cur.copy(backupNas = it), it) }
            GroupDivider()
            SwitchRow(Icons.Default.SignalCellularAlt, "モバイル回線でも送る", "オフならWi-Fiのときだけ（外出先はTailscale経由）",
                cur.backupOnMobile, enabled = cur.backupNas) { save(cur.copy(backupOnMobile = it)) }
        }
        SectionTitle("受け取る（Synology → スマホ）")
        Group { ReceiveRow() }
        SectionTitle("状況")
        Group { BackupNowRow(only = "nas", title = "今すぐ同期") }
    }
}

/** Google Drive backup: connect, how much to keep, mobile data. */
@Composable
fun DriveBackupScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    var note by remember { mutableStateOf<String?>(null) }
    fun save(n: Settings, run: Boolean = false) = scope.launch { app.prefs.save(n); if (run) BackupWorker.runNow(ctx) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch {
            if (r.resultCode == Activity.RESULT_OK && app.drive.token() != null) { save(cur.copy(backupDrive = true), true); note = null }
            else note = "Googleドライブに接続できませんでした"
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("最近の写真だけを、自分のGoogleドライブ（フォルダ「PhotoHub」）にも置きます。範囲を過ぎてSynologyに入った写真は、ドライブから自動で片付けるので溜まりません。外出先ではVPNなしでもここから見られます。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        SectionTitle("設定")
        Group {
            SwitchRow(Icons.Default.Cloud, "Googleドライブに保存", note ?: if (cur.backupDrive) "オン" else "オフ", cur.backupDrive) { on ->
                if (!on) save(cur.copy(backupDrive = false))
                else scope.launch {
                    try {
                        val r = app.drive.authorize(ctx as Activity)
                        if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                        else save(cur.copy(backupDrive = true), true)
                    } catch (e: Exception) { note = "接続できませんでした: ${e.message}" }
                }
            }
            GroupDivider()
            SwitchRow(Icons.Default.SignalCellularAlt, "モバイル回線でも送る", "オフならWi-Fiのときだけ", cur.backupOnMobile) {
                save(cur.copy(backupOnMobile = it))
            }
        }
        SectionTitle("ドライブに置いておく範囲")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(listOf("months" to "期間", "gb" to "容量", "both" to "両方"), cur.driveMode, { save(cur.copy(driveMode = it)) })
                if (cur.driveMode != "gb") {
                    Text("直近 ${cur.driveMonths} か月", style = MaterialTheme.typography.titleSmall)
                    Slider(cur.driveMonths.toFloat(), { save(cur.copy(driveMonths = it.toInt().coerceAtLeast(1))) }, valueRange = 1f..24f, steps = 22)
                }
                if (cur.driveMode != "months") {
                    Text("最大 ${cur.driveGb} GB", style = MaterialTheme.typography.titleSmall)
                    Slider(cur.driveGb.toFloat(), { save(cur.copy(driveGb = it.toInt().coerceAtLeast(1))) }, valueRange = 1f..100f)
                }
                Text(if (cur.driveMode == "both") "どちらか先に達した方で区切ります。" else "", style = MaterialTheme.typography.bodySmall)
                Text("Googleの無料枠は15GBで、Gmailなどと共用です。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("状況")
        Group { BackupNowRow(only = "drive", title = "今すぐ保存") }
    }
}

/** What this phone keeps: the photo list from the NAS, its size, and how much opens instantly away from home. */
@Composable
fun DeviceScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(SyncWorker.ONCE).collectAsState(initial = emptyList())
    val running = work.firstOrNull()?.state == WorkInfo.State.RUNNING
    fun save(n: Settings) = scope.launch { app.prefs.save(n) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("Synologyの写真を外出先でもすぐ見られるよう、一覧と縮小画像をこのスマホに持っています。自宅のWi-Fiで充電中に自動で更新します。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        SectionTitle("Synologyの写真の一覧")
        Group { ReceiveRow() }
        SectionTitle("使う容量")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上限 ${"%.1f".format(cur.storageCapMb / 1024.0)} GB（縮小画像は10万枚で約2GB）", style = MaterialTheme.typography.titleSmall)
                Slider(cur.storageCapMb.toFloat(), { save(cur.copy(storageCapMb = (it / 512).toInt() * 512)) }, valueRange = 1024f..16384f)
                Text("外出先ですぐ開ける期間：直近 ${cur.previewMonths} か月", style = MaterialTheme.typography.titleSmall)
                Slider(cur.previewMonths.toFloat(), { save(cur.copy(previewMonths = it.toInt())) }, valueRange = 0f..24f, steps = 23)
                Text("今の使用量：縮小画像 ${humanBytes(app.files.thumbBytes())}・すぐ開ける画像 ${humanBytes(app.files.previewBytes())}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionTitle("困ったとき")
        Group {
            SettingRow(Icons.Default.Build, "一覧を作り直す", "表示がおかしいときに。自宅のWi-Fiで行ってください", onClick = {
                scope.launch { app.prefs.resetCursor(); app.db.media().clearRemote(); SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "作り直しを始めました", Toast.LENGTH_SHORT).show() }
            })
        }
    }
}

/** App updates and sharing the app with family. */
@Composable
fun AppScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SectionTitle("更新")
        Group {
            UpdateRow()
            GroupDivider()
            SwitchRow(Icons.Default.SystemUpdate, "自動で更新", "新しい版をWi-Fiで取り込んで入れます", cur.autoUpdate) {
                scope.launch { app.prefs.save(cur.copy(autoUpdate = it)) }
            }
        }
        SectionTitle("家族に送る")
        Group { ShareAppRows() }
        Text("PhotoHub ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
    }
}

/** Live state of whichever backup is running (the "now" one or the hourly one): one entry per running part. */
data class BackupLive(val running: Boolean, val parts: List<com.photohub.app.data.BackupStep>)

@Composable
fun rememberBackupLive(): BackupLive {
    val ctx = LocalContext.current
    val wm = WorkManager.getInstance(ctx)
    val once by wm.getWorkInfosForUniqueWorkFlow(BackupWorker.ONCE).collectAsState(initial = emptyList())
    val periodic by wm.getWorkInfosForUniqueWorkFlow(BackupWorker.PERIODIC).collectAsState(initial = emptyList())
    val w = (once + periodic).firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return BackupLive(false, emptyList())
    val p = w.progress
    val parts = (p.getString("active") ?: "").split(',').filter { it.isNotBlank() }.map { ph ->
        com.photohub.app.data.BackupStep(ph, p.getString("${ph}_label") ?: "", p.getInt("${ph}_done", 0), p.getInt("${ph}_total", 0))
    }
    return BackupLive(true, parts)
}

/** Progress card: one bar per running part (Synology and Google Drive run side by side). */
@Composable
fun BackupProgress(live: BackupLive, only: String? = null) {
    if (!live.running) return
    val shown = live.parts.filter { only == null || it.phase == only || it.phase == "prepare" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text("同期・保存中", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton({ com.photohub.app.data.BackupEngine.stopRequested.set(true) }) { Text("止める") }
        }
        if (shown.isEmpty()) {
            Text(if (only == null) "準備しています" else "待機中です（ほかの手順を実行中）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        shown.forEach { st ->
            val place = when (st.phase) { "nas" -> PLACES.first { it.key == "nas" }; "drive" -> PLACES.first { it.key == "drive" }; else -> PLACES.first { it.key == "phone" } }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(place.icon, null, tint = place.color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(st.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (st.total > 0) Text("${st.done} / ${st.total}（${(st.fraction * 100).toInt()}%）",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (st.total > 0) androidx.compose.material3.LinearProgressIndicator(progress = { st.fraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp), color = place.color)
                else androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = place.color)
            }
        }
    }
}

/** Receiving from the Synology: the photo list and thumbnails (opened from "Synologyと同期" and "このスマホ"). */
@Composable
fun ReceiveRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val s by app.prefs.settings.collectAsState(initial = null)
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(SyncWorker.ONCE).collectAsState(initial = emptyList())
    val w = work.firstOrNull()
    val running = w?.state == WorkInfo.State.RUNNING || w?.state == WorkInfo.State.ENQUEUED
    val last = s?.lastSyncAt ?: 0L
    SettingRow(Icons.Default.Refresh, "Synologyの写真の一覧と縮小画像を受け取る",
        when {
            w?.state == WorkInfo.State.RUNNING -> "受け取り中… ${w.progress.getString("msg") ?: ""}"
            w?.state == WorkInfo.State.ENQUEUED -> "Synologyにつながるのを待っています"
            last > 0 -> "最後に受け取った日時 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}・アプリを開くたびに自動で受け取ります"
            else -> "まだ受け取っていません"
        }, enabled = !running, onClick = { SyncWorker.runNow(ctx) })
}

@Composable
fun BackupNowRow(only: String? = null, title: String = "今すぐ実行") {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val last by app.prefs.lastBackup.collectAsState(initial = 0L to "")
    val live = rememberBackupLive()
    if (live.running) { BackupProgress(live, only); return }
    val sub = if (last.first > 0) "前回 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last.first))}　${last.second}"
        else "まだ実行していません"
    SettingRow(Icons.Default.CloudUpload, title, sub, onClick = { BackupWorker.runNow(ctx); if (only == "nas") SyncWorker.runNow(ctx) })
}

@Composable
fun PlaceSetting() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { current = try { app.net.api()?.me()?.takeoutTarget } catch (e: Exception) { null } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(if (current == "shared") Icons.Default.FamilyRestroom else Icons.Default.Lock, null)
            Spacer(Modifier.size(12.dp))
            Text("GoogleフォトやGoogleドライブから取り込んだ写真を、Synologyのどこに置くか", style = MaterialTheme.typography.bodyMedium)
        }
        if (current == null) Text("Synologyにつながると選べます", style = MaterialTheme.typography.bodySmall)
        else Segmented(listOf("personal" to "自分だけ", "shared" to "家族で共有"), current!!, { key ->
            scope.launch { try { app.net.api()!!.setMySettings(MySettings(key)); current = key } catch (_: Exception) {} }
        })
        Text("スマホから直接送った写真は、Synology Photosのバックアップと同じ場所（自分の個人スペース）に入ります。変更は次に取り込む写真から。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FreeUpSpaceRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var freeable by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val olderThan = 30L * 24 * 3600 * 1000
    LaunchedEffect(refresh) { val l = app.db.media().localOnNas(System.currentTimeMillis() - olderThan); freeable = l.size to l.sumOf { it.size } }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch { app.indexer.scan(); refresh++ }
    }
    val f = freeable
    SettingRow(Icons.Default.DataSaverOn, "空き容量を増やす",
        when { f == null -> "計算中…"; f.first == 0 -> "消せる写真はありません（Synologyに保存済みで30日以上前のもの）"
            else -> "Synologyに保存済みの写真 ${f.first} 枚（${humanBytes(f.second)}）をスマホから削除。消してもこのアプリで見られます" },
        enabled = (f?.first ?: 0) > 0 && Build.VERSION.SDK_INT >= 30, onClick = {
            scope.launch {
                val uris = app.db.media().localOnNas(System.currentTimeMillis() - olderThan).map { android.net.Uri.parse(it.uri) }
                if (uris.isNotEmpty()) delete.launch(IntentSenderRequest.Builder(
                    MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
            }
        })
}

@Composable
fun UpdateRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsState()
    val (sub, action) = when (val x = st) {
        is Updater.State.Available -> "新しい版 ${x.release.versionName} があります。押すと更新" to { scope.launch { app.updater.download(x.release)?.let { if (app.updater.canInstall()) app.updater.install(it) else ctx.startActivity(app.updater.installPermissionIntent()) } }; Unit }
        is Updater.State.Ready -> "${x.release.versionName} の準備ができました。押すとインストール" to { if (app.updater.canInstall()) app.updater.install(x.file) else ctx.startActivity(app.updater.installPermissionIntent()) }
        is Updater.State.Downloading -> "ダウンロード中 ${x.percent}%" to {}
        Updater.State.Installing -> "インストール中…" to {}
        Updater.State.UpToDate -> "最新です（${app.updater.currentVersionName}）。押すと確認" to { scope.launch { app.updater.check() }; Unit }
        is Updater.State.Failed -> x.message to { scope.launch { app.updater.check() }; Unit }
        else -> "確認中…" to {}
    }
    SettingRow(Icons.Default.SystemUpdate, "アプリの更新", sub, onClick = action)
}

@Composable
fun ShareAppRows() {
    val ctx = LocalContext.current
    SettingRow(Icons.Default.Share, "アプリを家族に送る", "LINEやメールで、最新版のダウンロードリンクを送ります", onClick = {
        val text = "PhotoHub（家族の写真アプリ）のダウンロードはこちら：\n$APP_DOWNLOAD_URL\n" +
            "開いたら「この提供元のアプリを許可」をオンにしてインストールし、自宅のWi-FiでSynologyのユーザー名とパスワードでログインしてください。"
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text), "アプリを送る"))
    })
    GroupDivider()
    SettingRow(Icons.Default.ContentCopy, "ダウンロードリンクをコピー", APP_DOWNLOAD_URL, onClick = {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PhotoHub", APP_DOWNLOAD_URL))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
    })
}


/** Thin bar under the top bar while the NAS list / thumbnails are loading. */
@Composable
fun NasListBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(com.photohub.app.data.SyncWorker.ONCE)
        .collectAsState(initial = emptyList())
    val w = work.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        Text(w.progress.getString("msg") ?: "Synologyの写真の一覧を確認中…", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}
