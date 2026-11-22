package com.photohub.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.SyncWorker
import com.photohub.app.data.api.Candidate
import com.photohub.app.data.api.Job
import com.photohub.app.data.api.SimilarGroup
import com.photohub.app.data.api.Status
import kotlinx.coroutines.launch
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.DateFormat
import java.util.Date

@Composable
fun <T> remote(key: Any = Unit, load: suspend (com.photohub.app.data.api.PhotoHubApi) -> T): Pair<T?, String?> {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var value by remember(key) { mutableStateOf<T?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    LaunchedEffect(key) {
        try {
            val api = app.net.api()
            if (api == null) error = "Synologyに接続できません（外出先ではTailscaleを確認）" else value = load(api)
        } catch (e: Exception) {
            error = "取得できません: ${e.message}"
        }
    }
    return value to error
}

/** NAS import log: what came in from Takeout, phones and Drive, and what was new / duplicate / failed. */
@Composable
fun ImportLogScreen() {
    val (jobs, err) = remote<List<Job>> { it.jobs() }
    var open by remember { mutableStateOf<Job?>(null) }
    if (jobs == null) { Text(err ?: "読み込み中…", Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(jobs, key = { it.id }) { j ->
            Column(Modifier.fillMaxWidth().clickable { if (j.error > 0) open = j }.padding(16.dp, 10.dp)) {
                Text("${sourceLabel(j.source)}" + (j.user?.let { "（$it）" } ?: "") + " ・ ${stateLabel(j.state)}",
                    style = MaterialTheme.typography.titleSmall)
                Text("新規 ${j.new} ・ 重複 ${j.duplicate} ・ 失敗 ${j.error}", color = if (j.error > 0) Color(0xFFD84315) else Color.Unspecified)
                Text("${j.startedAt.take(16).replace('T', ' ')}  ${j.archive}", style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
    open?.let { j ->
        val (errs, e2) = remote(j.id) { it.jobErrors(j.id) }
        AlertDialog(onDismissRequest = { open = null }, confirmButton = { TextButton({ open = null }) { Text("閉じる") } },
            title = { Text("#${j.id} のエラー") },
            text = {
                LazyColumn { items(errs ?: emptyList()) { Text("${it.path}\n${it.message ?: ""}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) } }
                if (errs == null) Text(e2 ?: "読み込み中…")
            })
    }
}

private fun sourceLabel(s: String) = when (s) {
    "takeout" -> "Googleフォト（Takeout）"; "phone" -> "スマホ・ドライブから"; "phone_scan" -> "Synologyの写真の確認"; else -> s
}

private fun stateLabel(s: String) = when (s) {
    "done" -> "完了"; "failed" -> "失敗あり"; "running" -> "処理中"; "dry_run" -> "試運転（見込み）"; else -> s
}

/** Similar photos: pick the ones to keep; the rest go to the NAS trash folder. */
@Composable
fun SimilarScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val (groups, err) = remote<List<SimilarGroup>>(refresh) { it.similar() }
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<String?>(null) }
    if (groups == null) { Text(err ?: "読み込み中…", Modifier.padding(16.dp)); return }
    if (groups.isEmpty()) { Text("類似の候補はありません", Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item { Text("残す写真をタップして選び、「決定」を押します。残さない写真はSynologyのゴミ箱フォルダへ移ります（削除はしません）。" +
            "共有スペースの写真は管理者だけが、個人スペースの写真は本人だけが整理できます。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
        items(groups, key = { it.id }) { g ->
            var keep by remember(g.id) { mutableStateOf(setOf(g.members.maxByOrNull { (it.w ?: 0) * (it.h ?: 0) }?.mediaId ?: 0L)) }
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(if (g.space == "shared") "共有スペース" else "あなたの個人スペース", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(g.members, key = { it.mediaId }) { m ->
                            val selected = m.mediaId in keep
                            Column(Modifier.clickable { keep = if (selected) keep - m.mediaId else keep + m.mediaId }) {
                                val model: Any? = if (app.files.hasThumb(m.hash)) app.files.thumb(m.hash) else base?.let { "$it/api/thumb/${m.hash}" }
                                AsyncImage(model, m.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(120.dp).border(if (selected) 4.dp else 0.dp, MaterialTheme.colorScheme.primary))
                                Text(if (selected) "残す" else "ゴミ箱へ", style = MaterialTheme.typography.labelMedium)
                                Text("${m.w ?: "?"}×${m.h ?: "?"} ${humanBytes(m.bytes)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Button(enabled = keep.isNotEmpty(), onClick = {
                        scope.launch {
                            info = try {
                                val r = app.net.api()!!.resolve(g.id, com.photohub.app.data.api.KeepBody(keep.toList()))
                                if (r.applied) { refresh++; "移動しました: ${r.actions.size} 件" }
                                else "試運転中のため、まだ実行していません。本番にすると次のようにします: \n" + r.actions.joinToString("\n")
                            } catch (e: Exception) { "失敗しました: ${e.message}" }
                        }
                    }) { Text("決定") }
                }
            }
        }
    }
    info?.let { AlertDialog(onDismissRequest = { info = null }, confirmButton = { TextButton({ info = null }) { Text("OK") } }, text = { Text(it) }) }
}

/** Months of photos that are safely on the NAS and may be deleted from Google Photos. */
@Composable
fun GoogleCleanupScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var refresh by remember { mutableIntStateOf(0) }
    val (rows, err) = remote<List<Candidate>>(refresh) { it.candidates() }
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf<Candidate?>(null) }
    if (rows == null) { Text(err ?: "読み込み中…", Modifier.padding(16.dp)); return }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Text("あなたのGoogleアカウントの分です。Synologyに保存されハッシュが一致した、撮影から一定期間たった写真・動画です。PhotoHubはGoogleから削除しません。" +
                "Googleフォトのアプリで該当する月を削除したら「削除した」を押してください。", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
        }
        if (rows.isEmpty()) item { Text("候補はまだありません", Modifier.padding(8.dp)) }
        items(rows, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${c.month} ・ ${if (c.match == "exact") "完全一致" else "節約画質（似ている）・数枚確認してから"}",
                        style = MaterialTheme.typography.titleSmall)
                    Text("写真 ${c.photos} 枚 ・ 動画 ${c.videos} 本")
                    if (c.videos > 0) Text("動画は削除後、Synologyだけに残ります（外部の控えはありません）。", color = Color(0xFFD84315),
                        style = MaterialTheme.typography.bodySmall)
                    if (c.confirmedAt != null) Text("確認済み ${c.confirmedAt.take(10)}", style = MaterialTheme.typography.bodySmall)
                    else OutlinedButton(onClick = { ask = c }) { Text("Googleから削除した") }
                }
            }
        }
    }
    ask?.let { c ->
        AlertDialog(onDismissRequest = { ask = null }, title = { Text("${c.month} を確認済みにしますか？") },
            text = { Text("Googleフォトのアプリで、この月の写真 ${c.photos} 枚・動画 ${c.videos} 本を削除した場合だけ押してください。") },
            confirmButton = { TextButton({
                ask = null
                scope.launch { try { app.net.api()!!.confirm(c.id, "{}".toRequestBody()); refresh++ } catch (_: Exception) {} }
            }) { Text("削除した") } },
            dismissButton = { TextButton({ ask = null }) { Text("やめる") } })
    }
}
