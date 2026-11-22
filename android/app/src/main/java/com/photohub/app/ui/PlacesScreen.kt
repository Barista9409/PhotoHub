package com.photohub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.FolderEntry
import com.photohub.app.data.api.MyFolders
import com.photohub.app.data.api.MySettings
import kotlinx.coroutines.launch

/**
 * Each person's own places on the Synology (they differ per person, so they are set in the app):
 * where photos from this phone go, where Cloud Sync puts their Google Drive, where Google photos go.
 */
@Composable
fun SynologyPlacesScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var f by remember { mutableStateOf<MyFolders?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }   // "backup" / "drive"
    var why by remember { mutableStateOf("読み込み中…") }
    suspend fun load() {
        val api = app.net.api()
        if (api == null) { why = "Synologyにつながりません。自宅のWi-Fiか、外出先ならTailscaleをオンにしてください。"; return }
        f = try { api.myFolders() } catch (e: retrofit2.HttpException) {
            why = if (e.code() == 404) "Synology側（サーバー）が古い版です。新しい版に更新してください（導入ガイド「サーバー（Synology側）を新しい版にする」）。更新できたかは「更新と共有」→「Synology側の版」で確かめられます。"
                  else "Synologyでエラーが出ました（${e.code()}）"
            null
        } catch (e: Exception) { why = "Synologyにつながりません（${e.message}）"; null }
    }
    LaunchedEffect(Unit) { load() }
    fun save(body: MySettings) = scope.launch {
        error = try { app.net.api()!!.setMySettings(body); load(); null } catch (e: Exception) { "保存できませんでした（${e.message}）" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("写真をSynologyのどこに置くか、どこから読むかを、自分用に決めます。家族それぞれで違ってかまいません。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        val cur = f
        if (cur == null) {
            Text(why, modifier = Modifier.padding(20.dp))
            return@Column
        }
        SectionTitle("このスマホから送った写真の保存先")
        Group {
            SettingRow(Icons.Default.PhoneAndroid, "保存するフォルダ",
                if (cur.backupDir.isBlank()) "自動：${cur.root}/MobileBackup/<機種名>（Synology Photosのバックアップと同じ）"
                else "${cur.root}/${cur.backupDir}", onClick = { pick = "backup" })
        }
        Text("Synology Photosアプリのバックアップ先を変えている場合は、同じフォルダを選んでください。同じ写真が2枚にならないようにするためです。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp))

        SectionTitle("Googleドライブのフォルダ（Cloud Sync）")
        Group {
            val shown = cur.driveRoot.ifBlank { cur.driveDetected }
            SettingRow(Icons.Default.Cloud, "Googleドライブを同期しているフォルダ",
                when {
                    cur.driveRoot.isNotBlank() -> "${cur.home}/${cur.driveRoot}"
                    shown.isNotBlank() -> "自動で見つけました：${cur.home}/$shown"
                    else -> "見つかりません。Cloud Syncで同期しているフォルダを選んでください"
                }, onClick = { pick = "drive" })
        }
        Text("この中の「PhotoHub」フォルダ（ドライブに保存した写真）と「Takeout」フォルダ（Googleフォトの書き出し）を、PhotoHubが自動で読み取ります。取り込みが終わったTakeoutは自動で削除します。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp))

        SectionTitle("Googleから取り込んだ写真の保存先")
        Group { PlaceSetting() }

        SectionTitle("Synology Photosのアルバム")
        Group { PhotosLinkRows() }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(20.dp)) }
    }

    val cur = f
    if (pick != null && cur != null) {
        val backup = pick == "backup"
        val list = if (backup) cur.folders else cur.homeFolders
        val selected = if (backup) cur.backupDir else cur.driveRoot
        FolderDialog(
            title = if (backup) "スマホの写真を入れるフォルダ" else "Googleドライブを同期しているフォルダ",
            base = if (backup) cur.root else cur.home,
            autoLabel = if (backup) "自動（MobileBackup/<機種名>）" else "自動で探す",
            folders = list, selected = selected,
            onPick = { path -> pick = null; save(if (backup) MySettings(backupDir = path) else MySettings(driveRoot = path)) },
            onDismiss = { pick = null })
    }
}

@Composable
private fun FolderDialog(title: String, base: String, autoLabel: String, folders: List<FolderEntry>, selected: String,
                         onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item { FolderLine(autoLabel, 0, selected.isBlank()) { onPick("") } }
                if (folders.isEmpty()) item { Text("$base にフォルダがありません", style = MaterialTheme.typography.bodySmall) }
                items(folders, key = { it.path }) { e ->
                    FolderLine(e.path.substringAfterLast('/'), e.depth, e.path == selected) { onPick(e.path) }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("閉じる") } })
}

@Composable
private fun FolderLine(label: String, depth: Int, on: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.clickable(onClick = onClick).padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        RadioButton(on, onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}


/** Link to Synology Photos albums: status, a read-only connection check whose report can be copied, unlink. */
@Composable
fun PhotosLinkRows() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var linked by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { linked = try { app.net.api()?.me()?.photosLinked } catch (e: Exception) { null } }
    SettingRow(Icons.Default.PhotoAlbum, "アルバムの同期",
        when (linked) {
            true -> "連携しています（パスワードは暗号化してSynologyに保存）"
            false -> "連携していません。ログアウトして、ログイン画面で「Synology Photosのアルバムも同期する」にチェックを入れてログインし直してください"
            null -> "Synologyにつながると表示されます"
        })
    if (linked == true) {
        GroupDivider()
        SettingRow(Icons.Default.NetworkCheck, if (busy) "確かめています…" else "接続を確かめる",
            "Synology Photosのアルバムを読み取れるか確かめます（何も変えません）。結果をコピーしてClaudeに送ってください",
            enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    report = try { app.net.api()!!.synologyProbe().let { (if (it.ok) "OK\n" else "NG\n") + it.report } }
                        catch (e: Exception) { "失敗: ${e.message}" }
                    busy = false
                }
            })
        GroupDivider()
        SettingRow(Icons.Default.LinkOff, "連携をやめる", "保存したパスワードを消します", onClick = {
            scope.launch { try { app.net.api()!!.synologyUnlink(); linked = false } catch (_: Exception) {} }
        })
    }
    report?.let { text ->
        AlertDialog(onDismissRequest = { report = null }, title = { Text("接続の確認結果") },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) { item { Text(text, style = MaterialTheme.typography.bodySmall) } } },
            confirmButton = { TextButton({
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PhotoHub", text))
                report = null
            }) { Text("コピーして閉じる") } },
            dismissButton = { TextButton({ report = null }) { Text("閉じる") } })
    }
}
