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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.BugReport
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
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
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

/** The guides, always the latest version, opened in the browser: for the family, and for the administrator. */
const val GUIDE_URL = "https://github.com/${BuildConfig.UPDATE_REPO}/blob/main/docs/family.md"
const val ADMIN_GUIDE_URL = "https://github.com/${BuildConfig.UPDATE_REPO}/blob/main/docs/setup.md"

fun openGuide(ctx: android.content.Context, admin: Boolean = false) {
    try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(if (admin) ADMIN_GUIDE_URL else GUIDE_URL))) }
    catch (_: Exception) { Toast.makeText(ctx, "ブラウザを開けませんでした", Toast.LENGTH_SHORT).show() }
}

/** Where the account hub's rows lead. */
enum class Dest(val route: String, val title: String) {
    DRIVE_BACKUP("backup_drive", "Googleドライブに保存する範囲"),
    SIMILAR("similar", "似ている写真"),
    CLEANUP("cleanup", "Googleフォトから消してよい写真"),
    MIGRATION("migration", "移行の手順"),
    IMPORT_LOG("import_log", "Synologyへの取り込み記録"),
    DEVICE("device", "このスマホの容量"),
    LIBRARY("library", "ライブラリの内訳"),
    TAKEOUT("takeout", "取り込みの状況"),
    PLACES("places", "Synologyの保存場所"),
    ORGANIZE("organize", "整理とゴミ箱"),
    TRASH("trash", "ゴミ箱"),
    DUPLICATES("duplicates", "まったく同じ写真"),
    LOGS("logs", "エラーの記録"),
    HISTORY("history", "変更履歴"),
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
        // take the Synology's away-from-home address when this phone has none yet
        status?.remote?.takeIf { it.isNotBlank() && app.prefs.current().remoteUrl.isBlank() }?.let {
            app.prefs.save(app.prefs.current().copy(remoteUrl = it))
        }
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
                // logging out is rare: an icon on your own card, not a menu item
                androidx.compose.material3.IconButton({ confirmLogout = true }) { Icon(Icons.AutoMirrored.Filled.Logout, "ログアウト") }
            }
            // one line per connection, green when connected, red when not
            var driveOk by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(cur.backupDrive) { driveOk = if (!cur.backupDrive) false else app.drive.token() != null }
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    base == null -> StatusPill("Synology：未接続", false)
                    base == cur.lanUrl -> StatusPill("Synology：接続済 (LAN)", true)
                    else -> StatusPill("Synology：接続済 (VPN)", true)
                }
                when (driveOk) {
                    null -> StatusPill("Google Drive：確認中", true)
                    true -> StatusPill("Google Drive：接続済", true)
                    false -> StatusPill("Google Drive：未接続", false)
                }
                // is everything sent? (running now / N waiting on this phone / done, with the time)
                val only by app.db.media().phoneOnlyCounts().collectAsState(initial = emptyList())
                val waiting = only.sumOf { it.n }
                when {
                    live.running -> StatusPill("同期：同期中…", true, busy = true)
                    waiting > 0 && cur.backupNas -> StatusPill("同期：未送信 $waiting 件", false)
                    last.first > 0 -> StatusPill("同期：完了 " + java.text.SimpleDateFormat(
                        if (System.currentTimeMillis() - last.first < 86_400_000L) "H:mm" else "M/d H:mm", java.util.Locale.JAPAN)
                        .format(java.util.Date(last.first)), true)
                    else -> StatusPill("同期：まだです", false)
                }
                if (status?.apply == false) StatusPill("試運転中", false)
            }
            if (base == null && cur.remoteUrl.isBlank())
                Text("外出先の接続先（Tailscale）が未設定です。下の「同期設定」→「外出先からの接続」で設定してください。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
        }
        if (status?.apply == false) {
            Column(Modifier.padding(16.dp, 8.dp).fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("試運転中：Synologyの写真はまだ動かしません", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("いまPhotoHubは、Synologyの写真を読んで一覧を作るだけです。スマホやGoogleから届いた写真をSynology Photosのフォルダに入れる、重複を片付ける、取り込み済みのTakeoutを消す、といった作業は、まだしていません（届いた写真は待っています）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (me?.admin == true) {
                    Text("一覧や件数に問題がなければ、本番に切り替えてください：Container Manager →「プロジェクト」→ photohub →「YAML設定」で PHOTOHUB_APPLY を \"true\" に変えて保存 → 再起動。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                } else {
                    Text("本番への切り替えは、管理者が行います。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
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
        // Groups that fold away (remembered on this phone); every setting sits in its group on this page,
        // a row opens a page only for a tool (tidy up, migration, folders, records). Order = how often it is needed.
        // 同期設定: what to send where (on/off) -> when (mobile data, battery) -> how to reach the Synology
        // (away from home, its folders); "sync now" with the last result at the top, the most used row
        CollapsibleGroup("sync", "同期設定", "送り先・通信・電池・外出先", openByDefault = true) {
            if (live.running) BackupProgress(live) else RunNowRow()
            GroupDivider()
            SwitchRow(Icons.Default.Dns, "Synologyに保存",
                if (cur.backupNas) "撮った写真を数分で送ります" else "オフ：見るだけ（送りません）", cur.backupNas) {
                scope.launch { app.prefs.save(app.prefs.current().copy(backupNas = it)); if (it) { BackupWorker.runNow(ctx); SyncWorker.runNow(ctx) } }
            }
            GroupDivider()
            DriveToggleRow(onOpen = { open(Dest.DRIVE_BACKUP) })
            GroupDivider()
            SwitchRow(Icons.Default.SignalCellularAlt, "モバイル回線でも送る・受け取る",
                "オフ：Wi-Fiのときだけ", cur.backupOnMobile) {
                scope.launch { app.prefs.save(app.prefs.current().copy(backupOnMobile = it)) }
            }
            BatteryRow()
            GroupDivider()
            RemoteAddressRow()
            GroupDivider()
            SettingRow(Icons.Default.Folder, "Synologyの保存場所", "写真を置くフォルダ",
                onClick = { open(Dest.PLACES) }) { Chevron() }
        }

        // データ管理: tidy up (most used) -> this phone's space -> what is where -> moving from Google (once)
        CollapsibleGroup("library", "データ管理", "整理・容量・内訳・移行", openByDefault = true) {
            // one door for tidying up: similar / same photos, suggestions, dates, free space and the trash are inside
            SettingRow(Icons.Default.CleaningServices, "整理とゴミ箱",
                (status?.let { if (it.similarOpen > 0) "似ている写真 ${it.similarOpen} 組・" else "" } ?: "") +
                    "似た写真・同じ写真・ぼやけた写真", onClick = { open(Dest.ORGANIZE) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.PhoneAndroid, "このスマホの容量", storageSummary(),
                onClick = { open(Dest.DEVICE) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.PieChart, "ライブラリの内訳", librarySummary(), onClick = { open(Dest.LIBRARY) }) { Chevron() }
            GroupDivider()
            SettingRow(Icons.Default.PhotoLibrary, "Googleフォトからの移行", "手順と取り込みの状況",
                onClick = { open(Dest.MIGRATION) }) { Chevron() }
        }

        // closed, the summary still says when an update is waiting
        CollapsibleGroup("app", "アプリ",
            (if (updater is Updater.State.Available || updater is Updater.State.Ready) "新しい版があります・" else "") +
                "更新・変更履歴・使い方・記録", openByDefault = false) {
            UpdateRow()
            GroupDivider()
            SwitchRow(Icons.Default.SystemUpdate, "自動で更新", "Wi-Fiのときに入れます", cur.autoUpdate) {
                scope.launch { app.prefs.save(app.prefs.current().copy(autoUpdate = it)) }
            }
            GroupDivider()
            SettingRow(Icons.Default.History, "変更履歴", "いまの版 ${BuildConfig.VERSION_NAME}・これまでの変更点",
                onClick = { open(Dest.HISTORY) }) { Chevron() }
            GroupDivider()
            ServerVersionRow()
            GroupDivider()
            ShareAppRows()
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Filled.HelpOutline, "使い方", "家族向けガイド",
                onClick = { openGuide(ctx) }) { Chevron() }
            if (me?.admin == true) {
                GroupDivider()
                SettingRow(Icons.AutoMirrored.Filled.HelpOutline, "導入ガイド（管理者向け）", "Synologyの準備など",
                    onClick = { openGuide(ctx, admin = true) }) { Chevron() }
            }
            GroupDivider()
            val crashed = remember { com.photohub.app.CrashLog.read(ctx) != null }
            SettingRow(Icons.Default.BugReport, "エラーの記録",
                if (crashed) "前回うまく動きませんでした。確かめてください" else "止まった・失敗した処理",
                onClick = { open(Dest.LOGS) }) { Chevron() }
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
        Group { DriveToggleRow(onOpen = {}) }
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
        SectionTitle("今すぐ実行")
        Group { if (rememberBackupLive().running) BackupProgress(rememberBackupLive(), only = "drive") else RunNowRow() }
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
        SectionTitle("使う容量")
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上限 ${"%.1f".format(cur.storageCapMb / 1024.0)} GB（縮小画像は10万枚で約2GB）", style = MaterialTheme.typography.titleSmall)
                Slider(cur.storageCapMb.toFloat(), { save(cur.copy(storageCapMb = (it / 512).toInt() * 512)) }, valueRange = 1024f..16384f)
                Text("外出先ですぐ開ける期間：直近 ${cur.previewMonths} か月", style = MaterialTheme.typography.titleSmall)
                Slider(cur.previewMonths.toFloat(), { save(cur.copy(previewMonths = it.toInt())) }, valueRange = 0f..24f, steps = 23)
                StorageUsage()
            }
        }
        SectionTitle("Synologyの写真の一覧")
        Group { ReceiveRow() }
        SectionTitle("困ったとき")
        Group {
            SettingRow(Icons.Default.Build, "一覧を作り直す", "表示がおかしいときに。自宅のWi-Fiで行ってください", onClick = {
                scope.launch { app.prefs.resetCursor(); app.delta.clear(); app.db.media().clearRemote(); SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "作り直しを始めました", Toast.LENGTH_SHORT).show() }
            })
        }
    }
}

/** App updates and sharing the app with family. */

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
    val running = w?.state == WorkInfo.State.RUNNING
    val last = s?.lastSyncAt ?: 0L
    SettingRow(Icons.Default.Refresh, "Synologyの写真の一覧と縮小画像を受け取る",
        when {
            w?.state == WorkInfo.State.RUNNING -> "受け取り中… ${w.progress.getString("msg") ?: ""}"
            w?.state == WorkInfo.State.ENQUEUED -> "Wi-Fiなどにつながるのを待っています。押すとすぐ始めます"
            w?.state == WorkInfo.State.FAILED -> "前回は ${w.outputData.getString("msg") ?: "うまくいきませんでした"}。押すともう一度受け取ります"
            last > 0 -> "最後に受け取った日時 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))}・アプリを開くたびに自動で受け取ります"
            else -> "まだ受け取っていません"
        }, enabled = !running, onClick = { SyncWorker.runNow(ctx) })
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
            scope.launch { try { app.net.api()!!.setMySettings(MySettings(takeoutTarget = key)); current = key } catch (_: Exception) {} }
        })
        Text("Googleフォト（Takeout）やGoogleドライブから入った写真が対象です。変更は次に取り込む写真から。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


@Composable
fun UpdateRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsState()
    var notesOf by remember { mutableStateOf<com.photohub.app.data.api.AppRelease?>(null) }
    fun install(r: com.photohub.app.data.api.AppRelease) = scope.launch {
        app.updater.download(r)?.let { if (app.updater.canInstall()) app.updater.install(it) else ctx.startActivity(app.updater.installPermissionIntent()) }
    }
    val (sub, action) = when (val x = st) {
        is Updater.State.Available -> "新しい版 ${x.release.versionName} があります。押すと変更点を見て更新できます" to { notesOf = x.release }
        is Updater.State.Ready -> "${x.release.versionName} の準備ができました。押すと変更点を見てインストール" to { notesOf = x.release }
        is Updater.State.Downloading -> "ダウンロード中 ${x.percent}%" to {}
        Updater.State.Installing -> "インストール中…" to {}
        Updater.State.UpToDate -> "最新です（${app.updater.currentVersionName}）。押すと確認" to { scope.launch { app.updater.check() }; Unit }
        is Updater.State.Failed -> x.message to { scope.launch { app.updater.check() }; Unit }
        else -> "確認中…" to {}
    }
    SettingRow(Icons.Default.SystemUpdate, "アプリの更新", sub, onClick = action)
    notesOf?.let { r -> UpdateDialog(r) { notesOf = null } }
}

/** What changed in the new version, and the button that downloads and installs it. */
@Composable
fun UpdateDialog(r: com.photohub.app.data.api.AppRelease, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val st by app.updater.state.collectAsState()
    AlertDialog(onDismissRequest = onDone,
        title = { Text("PhotoHub ${r.versionName} の変更点") },
        text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp)) {
            item { Text(r.notes.ifBlank { "（変更点の記載はありません）" }, style = MaterialTheme.typography.bodyMedium) } } },
        confirmButton = { TextButton({
            onDone()
            val x = st
            if (x is Updater.State.Ready) { if (app.updater.canInstall()) app.updater.install(x.file) else ctx.startActivity(app.updater.installPermissionIntent()) }
            else scope.launch {
                app.updater.download(r)?.let { if (app.updater.canInstall()) app.updater.install(it) else ctx.startActivity(app.updater.installPermissionIntent()) }
            }
        }) { Text("更新する") } },
        dismissButton = { TextButton(onDone) { Text("あとで") } })
}

/** This version's changes: a row on the app screen, and a dialog once right after an update. */

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("PhotoHub ${BuildConfig.VERSION_NAME} の変更点") },
        text = { androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item { Text(BuildConfig.RELEASE_NOTES.ifBlank { "（変更点の記載はありません）" }, style = MaterialTheme.typography.bodyMedium) } } },
        confirmButton = { TextButton(onDismiss) { Text("OK") } })
}

/** 設定 → アプリ → 変更履歴: what changed in each version, newest first (this one marked). */
@Composable
fun HistoryScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var list by remember { mutableStateOf<List<com.photohub.app.data.Updater.Change>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        list = try { app.updater.history() } catch (e: Exception) { failed = true; null }
    }
    // without the internet: at least this version's notes, which are inside the app
    val shown = list ?: if (failed) listOf(com.photohub.app.data.Updater.Change(BuildConfig.VERSION_NAME, "", BuildConfig.RELEASE_NOTES)) else null
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (failed) item {
            Text("インターネットにつながらないため、いまの版の変更点だけを表示しています。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown == null) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp)); Text("読み込み中…")
            }
        }
        items(shown.orEmpty().size) { i ->
            val c = shown!![i]
            val current = c.version == BuildConfig.VERSION_NAME
            Column(Modifier.fillMaxWidth().background(
                if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(16.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.version, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (current) StatusPill("いまの版", true)
                    else Text(c.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(c.notes.ifBlank { "（変更点の記載はありません）" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Shown once after the app was updated (not on a fresh install). */
@Composable
fun WhatsNewOnce() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("photohub_ui", android.content.Context.MODE_PRIVATE) }
    val seen = remember { prefs.getInt("seen_version", 0) }
    var show by remember { mutableStateOf(seen in 1 until BuildConfig.VERSION_CODE) }
    val app = ctx.applicationContext as PhotoHubApp
    LaunchedEffect(Unit) {
        // first time with this feature: already set up = it was an update, so show it; a fresh install does not
        if (seen == 0) { if (app.prefs.current().configured) show = true else prefs.edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply() }
    }
    if (show) WhatsNewDialog {
        show = false
        prefs.edit().putInt("seen_version", BuildConfig.VERSION_CODE).apply()
    }
}

@Composable
fun ShareAppRows() {
    val ctx = LocalContext.current
    // just the link (the share sheet offers copy too)
    SettingRow(Icons.Default.Share, "アプリを共有", "最新版のダウンロードリンクを送ります", onClick = {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, APP_DOWNLOAD_URL), "アプリを共有"))
    })
}


/** Thin bar under the top bar while the NAS list / thumbnails are loading. */
@Composable
fun NasListBanner(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(com.photohub.app.data.SyncWorker.ONCE)
        .collectAsState(initial = emptyList())
    val w = work.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Text(w.progress.getString("msg") ?: "Synologyの写真の一覧を確認中…", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            modifier = Modifier.padding(top = 4.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp))
    }
}


/** Google Drive on/off right on the settings page (asks Google once when turned on); the row opens the range. */
@Composable
fun DriveToggleRow(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    var note by remember { mutableStateOf<String?>(null) }
    fun turnOn() = scope.launch { app.prefs.save(app.prefs.current().copy(backupDrive = true)); BackupWorker.runNow(ctx); note = null }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch { if (r.resultCode == Activity.RESULT_OK && app.drive.token() != null) turnOn() else note = "Googleドライブに接続できませんでした" }
    }
    val range = when (cur.driveMode) { "gb" -> "最大 ${cur.driveGb} GB"; "both" -> "直近 ${cur.driveMonths} か月・最大 ${cur.driveGb} GB"; else -> "直近 ${cur.driveMonths} か月" }
    SettingRow(Icons.Default.Cloud, "Googleドライブにも保存（最近の写真と動画）",
        note ?: if (cur.backupDrive) "$range（押して変更）。古いものは自動で片付けます" else "オフ（押すと範囲を設定できます）",
        onClick = onOpen) {
        androidx.compose.material3.Switch(cur.backupDrive, { on ->
            if (!on) scope.launch { app.prefs.save(app.prefs.current().copy(backupDrive = false)) }
            else scope.launch {
                try {
                    val r = app.drive.authorize(ctx as Activity)
                    if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else turnOn()
                } catch (e: Exception) { note = "接続できませんでした: ${e.message}" }
            }
        })
    }
}

/** One button for everything that is on: send to / receive from the Synology and save to Drive. */
@Composable
fun RunNowRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val last by app.prefs.lastBackup.collectAsState(initial = 0L to "")
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    val what = listOfNotNull(if (cur.backupNas) "Synologyへの保存" else null, if (cur.backupDrive) "ドライブへの保存" else null)
    SettingRow(Icons.Default.CloudUpload, "今すぐ同期・保存",
        when {
            what.isEmpty() -> "Synologyの写真の一覧だけを受け取ります"
            last.first > 0 -> "前回 ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last.first))}　${last.second}"
            else -> what.joinToString("・") + "を今すぐ行います"
        }, onClick = { BackupWorker.runNow(ctx); SyncWorker.runNow(ctx) })
}


/** Where the app reaches the Synology away from home (Tailscale). Synology's Tailscale package usually
 *  hides its address from PhotoHub, so it can be typed in here (from the Tailscale app's device list). */
@Composable
fun RemoteAddressRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    var edit by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    SettingRow(Icons.Default.VpnKey, "外出先からの接続（Tailscale）",
        if (cur.remoteUrl.isBlank()) "未設定のため、外出先ではつながりません。押してSynologyのTailscaleのアドレスを入れてください"
        else cur.remoteUrl.removePrefix("http://"),
        onClick = { edit = cur.remoteUrl.removePrefix("http://").substringBefore(":"); result = null }) { Chevron() }
    edit?.let { v0 ->
        var v by remember { mutableStateOf(v0) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("外出先からの接続") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("スマホのTailscaleアプリを開き、デバイスの一覧でSynologyを押すと「100.」で始まるアドレスが出ます。それを入れてください。",
                        style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(v, { v = it.trim() }, singleLine = true,
                        label = { Text("例：100.101.102.103") })
                    result?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("つながりました")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                    Text("確かめるときは、スマホのTailscaleをオンにしてください（自宅のWi-Fiでも確かめられます）。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = v.isNotBlank() && !busy, onClick = {
                    val host = v.removePrefix("http://").removePrefix("https://").trimEnd('/')
                    val url = "http://" + (if (host.contains(":")) host else "$host:8765")
                    busy = true
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                okhttp3.OkHttpClient.Builder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                                    .newCall(okhttp3.Request.Builder().url("$url/api/health").build()).execute()
                                    .use { it.isSuccessful && it.body?.string()?.contains("photohub") == true }
                            } catch (e: Exception) { false }
                        }
                        app.prefs.save(app.prefs.current().copy(remoteUrl = url))
                        UiState.refreshBase(app, true)
                        busy = false
                        if (ok) { result = null; edit = null; android.widget.Toast.makeText(ctx, "つながりました。外出先でも使えます", android.widget.Toast.LENGTH_LONG).show() }
                        else result = "保存しましたが、今はつながりませんでした。Tailscaleがオンか、アドレスが合っているか確かめてください"
                    }
                }) { Text(if (busy) "確かめています…" else "保存して確かめる") }
            },
            dismissButton = { TextButton({ edit = null }) { Text("閉じる") } })
    }
}


/** "A new version is out" strip on the main tabs; Android always asks before installing, so one tap here. */
@Composable
fun UpdateBanner(onOpen: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val st by app.updater.state.collectAsState()
    val name = when (val x = st) {
        is Updater.State.Available -> x.release.versionName
        is Updater.State.Ready -> x.release.versionName
        else -> return
    }
    // tapping it shows what changed and updates right there (it is also shown on the settings page)
    var notes by remember { mutableStateOf<com.photohub.app.data.api.AppRelease?>(null) }
    notes?.let { r -> UpdateDialog(r) { notes = null } }
    val rel = when (val x = st) { is Updater.State.Available -> x.release; is Updater.State.Ready -> x.release; else -> null }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).clickable { notes = rel }
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("新しい版 $name があります", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
        Text("更新する ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}


/** What PhotoHub keeps on this phone, part by part, with a button to clear the temporary parts. */
@Composable
fun StorageUsage() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var u by remember { mutableStateOf<com.photohub.app.data.AppStorage.Usage?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { u = com.photohub.app.data.AppStorage.measure(app) }
    val x = u
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("このアプリが使っている容量：" + (x?.let { humanBytes(it.total) } ?: "計算中…"), style = MaterialTheme.typography.titleSmall)
        if (x != null) {
            listOf("縮小画像（一覧用）" to x.thumbs, "すぐ開ける画像" to x.previews, "画像の一時保存" to x.imageCache,
                "写真の一覧（データベース）" to x.database, "そのほか" to x.other).forEach { (label, b) ->
                Row { Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(humanBytes(b), style = MaterialTheme.typography.bodySmall) }
            }
            TextButton({ scope.launch { com.photohub.app.data.AppStorage.clearTemporary(app); refresh++ } }) {
                Text("一時保存を消す（${humanBytes(x.imageCache + x.other)}）")
            }
            Text("一時保存は、また必要になったときに取りに行くだけなので、消しても写真はなくなりません。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "使用中 1.2 GB" for the settings page. */
@Composable
fun storageSummary(): String {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var t by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { t = com.photohub.app.data.AppStorage.measure(app).total }
    return t?.let { "使用中 ${humanBytes(it)}・" } ?: ""
}






/**
 * Whether Android may hold back PhotoHub's background work to save battery. Always shown, with the
 * current state: exempt (as recommended) or not; a tap opens the system screen to change it.
 */
@Composable
fun BatteryRow() {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(ctx.packageName)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        exempt = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
    GroupDivider()
    SettingRow(if (exempt) Icons.Default.BatteryFull else Icons.Default.BatteryAlert,
        if (exempt) "電池の最適化：外しています" else "電池の最適化から外す（おすすめ）",
        if (exempt) "自動のバックアップが後回しにされません（おすすめの状態）。押すとAndroidの設定で戻せます"
        else "このままだと、Androidが自動のバックアップを何時間も後回しにすることがあります。押して「許可」を選んでください",
        onClick = {
            val intent = if (exempt) android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                else android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${ctx.packageName}"))
            try { ask.launch(intent) } catch (_: Exception) {
                ask.launch(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }) { Chevron() }
}


/** The Synology side's version, and whether it is the newest one (each step has a time limit, and a tap checks again). */
@Composable
fun ServerVersionRow() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var server by remember { mutableStateOf("確認中…") }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        fun name(b: com.photohub.app.data.Updater.ServerBuild) =
            if (b.number == null && b.at != null) "${b.at.monthValue}月${b.at.dayOfMonth}日の版" else b.label()
        // 1) what the Synology runs: the light health answer (the status page counts the whole library)
        val build = try {
            kotlinx.coroutines.withTimeoutOrNull(15_000) {
                app.net.api()?.health()?.get("build")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: ""
            }
        } catch (e: Throwable) { com.photohub.app.data.AppLog.error("Synology側の版", e); null }
        if (build == null) { server = "Synologyにつながりません（押すともう一度）"; return@LaunchedEffect }
        val mine = com.photohub.app.data.Updater.ServerBuild.parse(build)
        if (mine == null) { server = "とても古い版です。新しい版に更新してください"; return@LaunchedEffect }
        server = "${name(mine)}（最新か確認中…）"
        // 2) the newest one published
        val latest = try { kotlinx.coroutines.withTimeoutOrNull(15_000) { app.updater.latestServer() } }
            catch (e: Throwable) { com.photohub.app.data.AppLog.error("Synology側の最新版", e); null }
        server = when {
            latest == null -> "${name(mine)}（最新か確かめられませんでした。押すともう一度）"
            mine.sha == latest.sha -> "最新です（${name(latest)}）"
            else -> "古い版です（${name(mine)}）。最新は${name(latest)}。自動更新を入れていれば2〜3分で入れ替わります（入らないときは、タスクスケジューラの「最終実行時刻」が23:00になっているか確かめてください）"
        }
    }
    SettingRow(Icons.Default.Dns, "Synology側の版", server, onClick = { server = "確認中…"; round++ })
}
