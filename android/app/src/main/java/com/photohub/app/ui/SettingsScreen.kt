package com.photohub.app.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.Settings
import com.photohub.app.data.SyncWorker
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onScan: () -> Unit = {}, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var s by remember { mutableStateOf<Settings?>(null) }
    var test by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { s = app.prefs.current() }
    val cur = s ?: return
    val locPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        app.net.currentSsid()?.let { ssid -> s = cur.copy(homeSsid = ssid) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NASへの接続", style = MaterialTheme.typography.titleMedium)
        Text("ふつうは「QRコードで設定」で自動で入ります（管理者が photohub user invite で発行）。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onScan) { Text("QRコードで設定し直す") }
        OutlinedTextField(cur.lanUrl, { s = cur.copy(lanUrl = it) }, label = { Text("自宅（LAN）のアドレス") },
            placeholder = { Text("http://192.168.1.10:8765") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(cur.remoteUrl, { s = cur.copy(remoteUrl = it) }, label = { Text("外出先（Tailscale）のアドレス") },
            placeholder = { Text("http://100.x.y.z:8765") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(cur.token, { s = cur.copy(token = it) }, label = { Text("あなたのトークン（管理者が photohub user add で発行）") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(cur.homeSsid, { s = cur.copy(homeSsid = it) }, label = { Text("自宅のWi-Fi名（SSID）") },
                singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("今のWi-Fi") }
        }
        Text("Wi-Fi名の取得には位置情報の権限が必要です（Androidの仕様）。位置は送信しません。", style = MaterialTheme.typography.bodySmall)

        Text("端末に持つデータ", style = MaterialTheme.typography.titleMedium)
        Text("容量の上限: ${cur.storageCapMb} MB（サムネイル1枚約20KB、10万枚で約2GB）")
        Slider(cur.storageCapMb.toFloat(), { s = cur.copy(storageCapMb = (it / 256).toInt() * 256) }, valueRange = 1024f..32768f)
        Text("プレビューを先に取り込む期間: 直近 ${cur.previewMonths} か月")
        Slider(cur.previewMonths.toFloat(), { s = cur.copy(previewMonths = it.toInt()) }, valueRange = 0f..36f)
        OutlinedTextField(cur.previewAlbums.joinToString(", "), {
            s = cur.copy(previewAlbums = it.split(',').map(String::trim).filter(String::isNotEmpty).toSet())
        }, label = { Text("プレビューを持つアルバム（カンマ区切り）") }, modifier = Modifier.fillMaxWidth())

        TakeoutPlaceSetting()

        Text("アプリの更新", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("新しい版をWi-Fiで自動的に取り込んで更新する（GitHubから）", modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(cur.autoUpdate, { s = cur.copy(autoUpdate = it) })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    if (app.prefs.current().token != cur.token) app.switchUser()
                    app.prefs.save(cur)
                    UiState.refreshBase(app, force = true)
                    SyncWorker.schedule(ctx)
                    onDone()
                }
            }) { Text("保存") }
            OutlinedButton(onClick = {
                scope.launch {
                    if (app.prefs.current().token != cur.token) app.switchUser()
                    app.prefs.save(cur)
                    test = "確認中…"
                    test = try {
                        val api = app.net.api(force = true)
                        if (api == null) "どちらのアドレスにも接続できません" else {
                            val me = api.me()
                            val st = api.status()
                            "${me.name}さんとして接続できました（${app.net.baseUrl()}）: 写真 ${st.photos} 枚・動画 ${st.videos} 本"
                        }
                    } catch (e: Exception) { "接続できましたが認証に失敗しました: ${e.message}" }
                }
            }) { Text("接続テスト") }
        }
        test?.let { Text(it) }
        OutlinedButton(onClick = { scope.launch { app.prefs.resetCursor(); app.db.media().clearRemote(); SyncWorker.runNow(ctx) } }) {
            Text("NASの索引を作り直す")
        }
    }
}
