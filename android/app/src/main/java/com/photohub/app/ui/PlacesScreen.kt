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
    suspend fun load() { f = try { app.net.api()?.myFolders() } catch (e: Exception) { null } }
    LaunchedEffect(Unit) { load() }
    fun save(body: MySettings) = scope.launch {
        error = try { app.net.api()!!.setMySettings(body); load(); null } catch (e: Exception) { "保存できませんでした（${e.message}）" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("写真をSynologyのどこに置くか、どこから読むかを、自分用に決めます。家族それぞれで違ってかまいません。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))
        val cur = f
        if (cur == null) {
            Text("Synologyにつながると表示されます", modifier = Modifier.padding(20.dp))
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
