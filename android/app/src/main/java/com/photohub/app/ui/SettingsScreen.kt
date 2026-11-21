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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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

/** Link that always downloads the newest app (a fixed-name copy attached to every release). */
val APP_DOWNLOAD_URL = "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest/download/PhotoHub.apk"

/**
 * Settings, saved as soon as something changes. Ordered by how often people need them:
 * account → backup → where photos go → Google Photos migration → this phone → app → troubleshooting.
 */
@Composable
fun SettingsScreen(onLoggedOut: () -> Unit, onMigration: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    val base by UiState.base.collectAsState()
    var me by remember { mutableStateOf<Me?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var driveNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(base) { me = try { app.net.api()?.me() } catch (e: Exception) { null } }
    fun save(n: Settings, backup: Boolean = false) = scope.launch { app.prefs.save(n); if (backup) BackupWorker.runNow(ctx) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch {
            if (r.resultCode == Activity.RESULT_OK && app.drive.token() != null) { save(cur.copy(backupDrive = true), true); driveNote = null }
            else driveNote = "Googleドライブに接続できませんでした"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        // ---- account -------------------------------------------------------------------
        SectionTitle("アカウント")
        Group {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text((me?.name ?: "?").take(1).uppercase(), style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.size(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me?.let { "${it.name} さん" } ?: "ログイン中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (me?.admin == true) "管理者" else "家族", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val where = when {
                    base == null -> "NASにつながりません" to false
                    base == cur.lanUrl -> "自宅でつながっています" to true
                    else -> "外出先からつながっています" to true
                }
                StatusPill(where.first, where.second)
            }
            GroupDivider()
            SettingRow(Icons.AutoMirrored.Filled.Logout, "ログアウト", "別の家族がこのスマホを使うとき",
                onClick = { confirmLogout = true })
        }

        // ---- backup ----------------------------------------------------------------------
        SectionTitle("バックアップ")
        Group {
            SwitchRow(Icons.Default.Dns, "NASへ送る", "撮った写真を自宅のNASへ。外出先ではTailscale経由",
                cur.backupNas) { save(cur.copy(backupNas = it), it) }
            GroupDivider()
            SwitchRow(Icons.Default.Cloud, "Googleドライブにも置く",
                driveNote ?: "最近の写真だけをクラウドにも。NASに入ったものは範囲を過ぎると自動で片付けます",
                cur.backupDrive) { on ->
                if (!on) save(cur.copy(backupDrive = false))
                else scope.launch {
                    try {
                        val r = app.drive.authorize(ctx as Activity)
                        if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                        else save(cur.copy(backupDrive = true), true)
                    } catch (e: Exception) { driveNote = "接続できませんでした: ${e.message}" }
                }
            }
            if (cur.backupDrive) {
                Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ドライブに置いておく範囲", style = MaterialTheme.typography.labelLarge)
                    Segmented(listOf("months" to "期間", "gb" to "容量", "both" to "両方"), cur.driveMode,
                        { save(cur.copy(driveMode = it)) })
                    if (cur.driveMode != "gb") {
                        Text("直近 ${cur.driveMonths} か月", style = MaterialTheme.typography.bodyMedium)
                        Slider(cur.driveMonths.toFloat(), { save(cur.copy(driveMonths = it.toInt().coerceAtLeast(1))) },
                            valueRange = 1f..24f, steps = 22)
                    }
                    if (cur.driveMode != "months") {
                        Text("最大 ${cur.driveGb} GB", style = MaterialTheme.typography.bodyMedium)
                        Slider(cur.driveGb.toFloat(), { save(cur.copy(driveGb = it.toInt().coerceAtLeast(1))) }, valueRange = 1f..100f)
                        Text("Googleの無料枠は15GB（Gmailなどと共用）", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            GroupDivider()
            SwitchRow(Icons.Default.SignalCellularAlt, "モバイル回線でも送る", "オフならWi-Fiのときだけ", cur.backupOnMobile) {
                save(cur.copy(backupOnMobile = it))
            }
            GroupDivider()
            BackupNowRow()
        }

        // ---- where photos go -------------------------------------------------------------------
        SectionTitle("写真の置き場所")
        Group { PlaceSetting() }

        // ---- Google Photos migration ---------------------------------------------------------
        SectionTitle("Googleフォトからの移行")
        Group {
            SettingRow(Icons.Default.PhotoLibrary, "TakeoutのZIPをNASへ送る",
                "Googleフォトから書き出したZIPを選んで送ります。日時を整え、重複をまとめて取り込みます",
                onClick = onMigration)
        }

        // ---- this phone ----------------------------------------------------------------------
        SectionTitle("このスマホ")
        Group {
            FreeUpSpaceRow()
            GroupDivider()
            SettingRow(Icons.Default.Storage, "端末に持つデータ",
                "サムネイルとプレビュー：最大 ${cur.storageCapMb / 1024.0} GB・プレビューは直近 ${cur.previewMonths} か月分")
            Column(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp)) {
                Text("容量の上限", style = MaterialTheme.typography.labelLarge)
                Slider(cur.storageCapMb.toFloat(), { save(cur.copy(storageCapMb = (it / 512).toInt() * 512)) }, valueRange = 1024f..16384f)
                Text("外出先でもすぐ開ける期間", style = MaterialTheme.typography.labelLarge)
                Slider(cur.previewMonths.toFloat(), { save(cur.copy(previewMonths = it.toInt())) }, valueRange = 0f..24f, steps = 23)
            }
        }

        // ---- app ---------------------------------------------------------------------------
        SectionTitle("アプリ")
        Group {
            SwitchRow(Icons.Default.SystemUpdate, "自動で更新", "新しい版をWi-Fiで取り込んで入れます", cur.autoUpdate) {
                save(cur.copy(autoUpdate = it))
            }
            GroupDivider()
            UpdateRow()
            GroupDivider()
            ShareAppRows()
        }

        // ---- troubleshooting -----------------------------------------------------------------
        SectionTitle("困ったとき")
        Group {
            SettingRow(Icons.Default.Build, "NASの一覧を作り直す", "表示がおかしいときに。自宅のWi-Fiで行ってください", onClick = {
                scope.launch { app.prefs.resetCursor(); app.db.media().clearRemote(); SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "作り直しを始めました", Toast.LENGTH_SHORT).show() }
            })
        }
        Text("PhotoHub ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
    }

    if (confirmLogout) {
        AlertDialog(onDismissRequest = { confirmLogout = false },
            title = { Text("ログアウトしますか？") },
            text = { Text("このスマホに持っているNASの一覧とサムネイルを消します。NASやドライブの写真はそのままです。") },
            confirmButton = { TextButton({
                confirmLogout = false
                scope.launch { app.switchUser(); app.prefs.save(app.prefs.current().copy(token = "")); UiState.refreshBase(app, true); onLoggedOut() }
            }) { Text("ログアウト") } },
            dismissButton = { TextButton({ confirmLogout = false }) { Text("やめる") } })
    }
}

@Composable
private fun BackupNowRow() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val last by app.prefs.lastBackup.collectAsState(initial = 0L to "")
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(BackupWorker.ONCE).collectAsState(initial = emptyList())
    val running = work.firstOrNull()?.state == WorkInfo.State.RUNNING
    val sub = when {
        running -> "実行中… ${work.firstOrNull()?.progress?.getString("msg") ?: ""}"
        last.first > 0 -> "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last.first))}　${last.second}"
        else -> "まだ実行していません"
    }
    SettingRow(Icons.Default.CloudUpload, "今すぐバックアップ", sub, enabled = !running, onClick = { BackupWorker.runNow(ctx) })
}

@Composable
private fun PlaceSetting() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { current = try { app.net.api()?.me()?.takeoutTarget } catch (e: Exception) { null } }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(if (current == "shared") Icons.Default.FamilyRestroom else Icons.Default.Lock, null)
            Spacer(Modifier.size(12.dp))
            Text("スマホやGoogleフォトから取り込んだ写真を、NASのどこに置くか", style = MaterialTheme.typography.bodyMedium)
        }
        if (current == null) Text("NASにつながると選べます", style = MaterialTheme.typography.bodySmall)
        else Segmented(listOf("personal" to "自分だけ", "shared" to "家族で共有"), current!!, { key ->
            scope.launch { try { app.net.api()!!.setMySettings(MySettings(key)); current = key } catch (_: Exception) {} }
        })
        Text("変更は次に取り込む写真から。取り込み済みの写真はそのままです。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FreeUpSpaceRow() {
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
        when { f == null -> "計算中…"; f.first == 0 -> "消せる写真はありません（NASに保存済みで30日以上前のもの）"
            else -> "NASに保存済みの写真 ${f.first} 枚（${humanBytes(f.second)}）をスマホから削除。消してもこのアプリで見られます" },
        enabled = (f?.first ?: 0) > 0 && Build.VERSION.SDK_INT >= 30, onClick = {
            scope.launch {
                val uris = app.db.media().localOnNas(System.currentTimeMillis() - olderThan).map { android.net.Uri.parse(it.uri) }
                if (uris.isNotEmpty()) delete.launch(IntentSenderRequest.Builder(
                    MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
            }
        })
}

@Composable
private fun UpdateRow() {
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
private fun ShareAppRows() {
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
