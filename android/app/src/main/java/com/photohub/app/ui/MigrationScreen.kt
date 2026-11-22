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
import androidx.compose.material.icons.filled.Cloud
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
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photohub.app.data.TakeoutUploadWorker

/**
 * Google Photos -> Synology, once. Takeout is made into Google Drive and Cloud Sync brings it to the
 * Synology, where PhotoHub imports it and then deletes the ZIPs. Nothing goes through the phone.
 */
@Composable
fun MigrationScreen(open: (Dest) -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("Googleフォトの写真を、一度だけSynologyへ移します。スマホで送る必要はありません。",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp))

        SectionTitle("手順")
        Group {
            SettingRow(Icons.Default.Cloud, "1. TakeoutをGoogleドライブに作る",
                "takeout.google.com で「Googleフォト」だけを選び、配信方法「ドライブに追加」、形式 ZIP、サイズ 50GB。すでにドライブにあればそのままでOK")
            GroupDivider()
            SettingRow(Icons.Default.Download, "2. Synologyに届くのを待つ",
                "Cloud Syncがドライブの「Takeout」フォルダをSynologyに持ってきます。どのフォルダか → 「Synologyの保存場所」で確認・変更できます",
                onClick = { open(Dest.PLACES) })
            GroupDivider()
            SettingRow(Icons.Default.Inventory2, "3. Synologyが取り込む",
                "日時と場所を整え、すでにSynologyにある写真とは重複しないよう1枚にまとめます。終わったZIPは自動で削除します（ドライブ側のTakeoutも消えて、ドライブの容量が空きます）",
                onClick = { open(Dest.IMPORT_LOG) })
            GroupDivider()
            SettingRow(Icons.Default.TaskAlt, "4. Googleフォトを空にする",
                "Synologyに保存済みと確認できた写真を、月ごとに案内します。Googleフォトのアプリで消してください",
                onClick = { open(Dest.CLEANUP) })
        }
        Text("取り込みの様子は「Synologyへの取り込み記録」で見られます。30GBで数時間かかります。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp))
    }
}


/** Live view of the migration: ZIPs found, waiting, the running import with a bar, and the results. */
@Composable
fun TakeoutStatusScreen(open: (Dest) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SectionTitle("Takeoutの取り込み")
        Group { TakeoutStatusCard(open) }
    }
}

@Composable
private fun TakeoutStatusCard(open: (Dest) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.photohub.app.PhotoHubApp
    var st by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.photohub.app.data.api.TakeoutStatus?>(null) }
    var err by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {   // refresh every 5 seconds while this screen is open
            try { st = app.net.api()?.takeoutStatus(); err = if (st == null) "Synologyにつながりません" else null }
            catch (e: retrofit2.HttpException) { err = if (e.code() == 404) "Synology側（サーバー）が古い版です。更新すると表示されます" else "エラー（${e.code()}）" }
            catch (e: Exception) { err = "Synologyにつながりません" }
            kotlinx.coroutines.delay(5000)
        }
    }
    val s = st
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s == null) { Text(err ?: "読み込み中…", style = MaterialTheme.typography.bodyMedium); return@Column }
        if (!s.apply) {
            Text("試運転中：下の件数は見込みです。写真はまだ動かしていません（ZIPも消しません）。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        val srcBytes = s.sources.sumOf { it.bytes }
        Text(if (s.sources.isEmpty()) "Googleドライブから届いたZIP：まだありません（Cloud Syncのダウンロード中か、届くフォルダが違う可能性があります）"
             else "Googleドライブから届いたZIP：${s.sources.size} 個・${humanBytes(srcBytes)}", style = MaterialTheme.typography.bodyMedium)
        if (s.waiting.isNotEmpty()) Text("取り込み待ち・取り込み中：${s.waiting.size} 個（${humanBytes(s.waiting.sumOf { it.bytes })}）",
            style = MaterialTheme.typography.bodyMedium)
        val c = s.current
        if (c != null && !c.finished) {
            val label = when (c.step) {
                "extract" -> "ZIPを展開中"; "organize" -> "日時・アルバムを整理中"; "analyse" -> "写真を確認中"
                "import" -> if (c.dryRun) "取り込みの見込みを計算中" else "Synologyへ取り込み中"; else -> "処理中"
            }
            Text("$label：${c.archive}", style = MaterialTheme.typography.titleSmall)
            if (c.total > 0) {
                LinearProgressIndicator(progress = { c.done.toFloat() / c.total }, modifier = Modifier.fillMaxWidth())
                Text("${c.done} / ${c.total}（${c.done * 100 / c.total}%）　新規 ${c.new}・重複 ${c.duplicate}・失敗 ${c.error}",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("大きいZIPは、展開と整理だけで1時間以上かかることがあります", style = MaterialTheme.typography.bodySmall)
            }
        }
        // the ZIPs themselves: deleted after an error-free import, or still lying around
        Text("TakeoutのZIP", style = MaterialTheme.typography.titleSmall)
        if (s.deleted.isNotEmpty()) Text("取り込み後に削除済み：${s.deleted.size} 個（最新 ${s.deleted.first().at.take(16).replace('T', ' ')}）",
            style = MaterialTheme.typography.bodySmall)
        if (s.leftover.isNotEmpty()) {
            Text("取り込み済みなのに残っているZIP：${s.leftover.size} 個（${humanBytes(s.leftover.sumOf { it.bytes })}）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            androidx.compose.material3.OutlinedButton({
                scope.launch { msg = try { "${app.net.api()!!.takeoutCleanup()["deleted"] ?: 0} 個を削除しました" } catch (e: Exception) { "削除できませんでした" } }
            }) { Text("取り込み済みのZIPを削除する") }
        }
        if (s.deleted.isEmpty() && s.leftover.isEmpty())
            Text(if (s.apply) "取り込みがエラーなく終わったZIPは、自動で削除します" else "試運転中は削除しません（本番にすると、取り込み後に自動で削除します）",
                style = MaterialTheme.typography.bodySmall)
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (s.jobs.isNotEmpty()) {
            Text("結果", style = MaterialTheme.typography.titleSmall)
            s.jobs.take(5).forEach { j ->
                val state = when (j.state) { "done" -> "完了"; "failed" -> "失敗あり"; "running" -> "処理中"; "dry_run" -> "試運転（見込み）"; else -> j.state }
                Text("$state・新規 ${j.new}・重複 ${j.duplicate}・失敗 ${j.error}" + (j.finishedAt?.let { "（${it.take(16).replace('T', ' ')}）" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (j.error > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
            Text("失敗の理由は「Synologyへの取り込み記録」で見られます", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { open(Dest.IMPORT_LOG) })
        } else if (s.sources.isEmpty() && s.waiting.isEmpty() && c == null) {
            Text("まだ取り込みはありません", style = MaterialTheme.typography.bodySmall)
        }
    }
}
