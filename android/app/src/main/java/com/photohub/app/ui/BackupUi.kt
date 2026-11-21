package com.photohub.app.ui

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.BackupWorker
import com.photohub.app.data.Settings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Backup settings (in Settings). Changes are saved immediately. */
@Composable
fun BackupSettings() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val s by app.prefs.settings.collectAsState(initial = null)
    val cur = s ?: return
    var note by remember { mutableStateOf<String?>(null) }
    fun save(n: Settings) = scope.launch { app.prefs.save(n); BackupWorker.runNow(ctx) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        scope.launch {
            if (r.resultCode == Activity.RESULT_OK && app.drive.token() != null) {
                save(cur.copy(backupDrive = true)); note = "Googleドライブに接続しました"
            } else note = "接続できませんでした"
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("バックアップ", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NASへ送る（自宅のWi-Fi、外出先はTailscale経由）", Modifier.weight(1f))
            Switch(cur.backupNas, { save(cur.copy(backupNas = it)) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Googleドライブに最近の写真を置く", Modifier.weight(1f))
            Switch(cur.backupDrive, { on ->
                if (!on) save(cur.copy(backupDrive = false))
                else scope.launch {
                    try {
                        val r = app.drive.authorize(ctx as Activity)
                        if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
                        else { save(cur.copy(backupDrive = true)); note = "Googleドライブに接続しました" }
                    } catch (e: Exception) { note = "接続できませんでした: ${e.message}" }
                }
            })
        }
        if (cur.backupDrive) {
            Text("ドライブに置いておく範囲", style = MaterialTheme.typography.labelLarge)
            listOf("months" to "期間で決める", "gb" to "容量で決める", "both" to "両方（どちらか早い方）").forEach { (k, label) ->
                Row(Modifier.selectable(selected = cur.driveMode == k, onClick = { save(cur.copy(driveMode = k)) }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = cur.driveMode == k, onClick = null); Text(label)
                }
            }
            if (cur.driveMode != "gb") {
                Text("直近 ${cur.driveMonths} か月")
                Slider(cur.driveMonths.toFloat(), { save(cur.copy(driveMonths = it.toInt().coerceAtLeast(1))) }, valueRange = 1f..24f)
            }
            if (cur.driveMode != "months") {
                Text("最大 ${cur.driveGb} GB（Googleの無料枠は15GBで、Gmailなどと共用）")
                Slider(cur.driveGb.toFloat(), { save(cur.copy(driveGb = it.toInt().coerceAtLeast(1))) }, valueRange = 1f..200f)
            }
            Text("範囲を外れた写真は、NASに入ったことを確かめてからドライブから消します。", style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("モバイル回線でもバックアップする", Modifier.weight(1f))
            Switch(cur.backupOnMobile, { save(cur.copy(backupOnMobile = it)) })
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Admin home: backup status and "free up space" (only photos already on the NAS). */
@Composable
fun BackupCard() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val last by app.prefs.lastBackup.collectAsState(initial = 0L to "")
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(BackupWorker.ONCE).collectAsState(initial = emptyList())
    val running = work.firstOrNull()?.state == WorkInfo.State.RUNNING
    var freeable by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    val olderThan = 30L * 24 * 3600 * 1000
    LaunchedEffect(refresh) {
        val l = app.db.media().localOnNas(System.currentTimeMillis() - olderThan)
        freeable = l.size to l.sumOf { it.size }
    }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        msg = if (r.resultCode == Activity.RESULT_OK) "スマホから削除しました（NASのものはそのまま見られます）" else "やめました"
        scope.launch { app.indexer.scan(); refresh++ }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("バックアップ", style = MaterialTheme.typography.titleMedium)
            Text(if (last.first > 0) "${DateFormat.getDateTimeInstance().format(Date(last.first))}　${last.second}" else "まだ実行していません")
            if (running) Text("実行中… ${work.firstOrNull()?.progress?.getString("msg") ?: ""}")
            OutlinedButton(enabled = !running, onClick = { BackupWorker.runNow(ctx) }) { Text("今すぐバックアップ") }
            Text("スマホの空き容量", style = MaterialTheme.typography.titleSmall)
            val f = freeable
            if (f == null) Text("計算中…")
            else if (f.first == 0) Text("NASに保存済みで、30日以上前の写真はありません")
            else {
                Text("NASに保存済みで30日以上前の写真 ${f.first} 枚（${humanBytes(f.second)}）をスマホから消せます。消してもこのアプリで見られます。")
                Button(onClick = {
                    scope.launch {
                        val uris = app.db.media().localOnNas(System.currentTimeMillis() - olderThan).map { android.net.Uri.parse(it.uri) }
                        if (Build.VERSION.SDK_INT >= 30 && uris.isNotEmpty()) {
                            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris)
                            delete.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                    }
                }) { Text("空き容量を増やす") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
