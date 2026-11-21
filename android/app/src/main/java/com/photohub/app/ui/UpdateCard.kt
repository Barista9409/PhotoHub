package com.photohub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.Updater.State
import kotlinx.coroutines.launch

/** "アプリの更新" card (admin home). New versions come from the family NAS. */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val u = app.updater
    val state by u.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (state is State.Idle) u.check() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("アプリの更新", style = MaterialTheme.typography.titleMedium)
            Text("この端末: ${u.currentVersionName}")
            when (val st = state) {
                State.Idle, State.Checking -> Text("確認中…")
                State.UpToDate -> Text("最新です")
                is State.Failed -> Text(st.message, color = MaterialTheme.colorScheme.error)
                is State.Available -> {
                    Text("新しい版 ${st.release.versionName}（${humanBytes(st.release.size)}）があります")
                    if (st.release.notes.isNotBlank()) Text(st.release.notes, style = MaterialTheme.typography.bodySmall)
                }
                is State.Downloading -> {
                    Text("ダウンロード中 ${st.percent}%")
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                is State.Ready -> {
                    Text("${st.release.versionName} の準備ができました")
                    if (st.release.notes.isNotBlank()) Text(st.release.notes, style = MaterialTheme.typography.bodySmall)
                }
                State.Installing -> Text("インストール中…（画面の確認が出たら「更新」を押してください）")
            }
            if (!u.canInstall() && (state is State.Available || state is State.Ready)) {
                Text("最初の1回だけ、「この提供元のアプリを許可」をオンにしてください。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { ctx.startActivity(u.installPermissionIntent()) }) { Text("許可の設定を開く") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val st = state) {
                    is State.Available -> Button(onClick = {
                        scope.launch { u.download(st.release)?.let { if (u.canInstall()) u.install(it) } }
                    }) { Text("今すぐ更新") }
                    is State.Ready -> Button(enabled = u.canInstall(), onClick = { u.install(st.file) }) { Text("インストール") }
                    else -> OutlinedButton(enabled = state !is State.Downloading && state !is State.Installing,
                        onClick = { scope.launch { u.check() } }) { Text("更新を確認") }
                }
            }
        }
    }
}
