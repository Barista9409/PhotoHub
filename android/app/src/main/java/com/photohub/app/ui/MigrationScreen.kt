package com.photohub.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photohub.app.data.TakeoutUploadWorker

/** Google Photos → NAS, once: pick the Takeout ZIPs on the phone and send them as they are. */
@Composable
fun MigrationScreen() {
    val ctx = LocalContext.current
    val work by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(TakeoutUploadWorker.UNIQUE).collectAsState(initial = emptyList())
    val w = work.lastOrNull()
    val running = w?.state == WorkInfo.State.RUNNING || w?.state == WorkInfo.State.ENQUEUED
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) TakeoutUploadWorker.start(ctx, uris)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SectionTitle("手順")
        Group {
            SettingRow(Icons.Default.Download, "1. Googleフォトを書き出す",
                "takeout.google.com で「Googleフォト」だけを選び、配信方法「ダウンロードリンクをメールで送信」、形式 ZIP。届いたメールから、このスマホにZIPをダウンロードします")
            GroupDivider()
            SettingRow(Icons.Default.FolderZip, "2. ZIPを選んでSynologyへ送る",
                "解凍せず、ZIPのまま選んでください。分かれたZIP（-001、-002 …）はまとめて選べます。自宅のWi-Fiで送ります")
            GroupDivider()
            SettingRow(Icons.Default.Inventory2, "3. Synologyが取り込む",
                "日時と場所を整え、すでにSynologyにある写真（スマホのバックアップなど）とは重複しないよう1枚にまとめます。結果は「Synologyへの取り込み記録」で見られます")
            GroupDivider()
            SettingRow(Icons.Default.TaskAlt, "4. 確認できたらGoogleフォトを片付ける",
                "「Googleフォトから消してよい写真」に、Synologyに入った月が出ます。Googleフォトのアプリで消してください")
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !running, modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }) {
                Text("ZIPを選んで送る")
            }
            when (w?.state) {
                WorkInfo.State.RUNNING -> {
                    val pct = w.progress.getInt(TakeoutUploadWorker.KEY_PCT, 0)
                    Text("${w.progress.getInt(TakeoutUploadWorker.KEY_INDEX, 1)} / ${w.progress.getInt(TakeoutUploadWorker.KEY_COUNT, 1)}　" +
                        "${w.progress.getString(TakeoutUploadWorker.KEY_NAME) ?: ""}　$pct%")
                    LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("アプリを閉じても続きます。途中で切れても、続きから送ります。", style = MaterialTheme.typography.bodySmall)
                }
                WorkInfo.State.ENQUEUED -> Text("自宅のWi-Fiにつながると送り始めます")
                WorkInfo.State.SUCCEEDED -> Text("送り終わりました。Synologyが順に取り込みます（大きいZIPは数時間かかります）")
                else -> {}
            }
        }
    }
}
