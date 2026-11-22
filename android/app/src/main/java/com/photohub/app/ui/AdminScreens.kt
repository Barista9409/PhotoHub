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
import com.photohub.app.data.Updater
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
    "done" -> "完了"; "failed" -> "失敗あり"; "running" -> "処理中"; "dry_run" -> "試運転（見込み）"; "interrupted" -> "中断（次の確認で続きから）"; else -> s
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
    var bulk by remember { mutableStateOf<com.photohub.app.data.api.BulkStatus?>(null) }
    var askBulk by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) { bulk = try { app.net.api()?.resolveAllStatus() } catch (_: Exception) { null } }
    LaunchedEffect(bulk?.running) {
        while (bulk?.running == true) {
            kotlinx.coroutines.delay(1500)
            bulk = try { app.net.api()?.resolveAllStatus() } catch (_: Exception) { bulk }
            if (bulk?.running == false) {
                val b = bulk!!
                info = if (!b.applied) "試運転中のため、まだ実行していません（Synologyの設定で本番にすると処理します）"
                       else "${b.done} 組を処理し、${b.trashed} 枚をゴミ箱に入れました" + (if (b.failed > 0) "（${b.failed} 組はできませんでした）" else "")
                com.photohub.app.data.SyncWorker.runNow(ctx)
                refresh++
            }
        }
    }
    if (askBulk) AlertDialog(onDismissRequest = { askBulk = false },
        title = { Text("すべておすすめで処理しますか？") },
        text = { Text("似ている写真 ${bulk?.open ?: 0} 組すべてで、おすすめ（いちばん大きい写真）だけを残し、ほかはゴミ箱に入れます。" +
            "この画面に出ていない組も含みます。30日間は「ゴミ箱」から戻せます。") },
        confirmButton = { TextButton({
            askBulk = false
            scope.launch { bulk = try { app.net.api()!!.resolveAll() } catch (e: Exception) { info = "始められませんでした（${e.message}）"; null } }
        }) { Text("処理する") } },
        dismissButton = { TextButton({ askBulk = false }) { Text("やめる") } })
    val allButton: @Composable () -> Unit = {
        val b = bulk
        if (b != null && (b.running || b.open > 0)) Button(enabled = !b.running, onClick = { askBulk = true }, modifier = Modifier.fillMaxWidth()) {
            Text(if (b.running) "処理中… ${b.done} / ${b.total} 組" else "すべておすすめで処理（全 ${b.open} 組）")
        }
    }
    if (groups == null) { Text(err ?: "読み込み中…", Modifier.padding(16.dp)); return }
    if (groups.isEmpty()) { Text("似ている写真はありません（Synologyに新しい写真が入るたびに探します）", Modifier.padding(16.dp)); return }
    // what to keep in each group: the recommended one (largest picture) unless changed
    val keeps = remember(groups) {
        androidx.compose.runtime.mutableStateMapOf<Long, Set<Long>>().apply {
            groups.forEach { g -> put(g.id, setOf(g.members.maxByOrNull { (it.w ?: 0) * (it.h ?: 0) }?.mediaId ?: 0L)) }
        }
    }
    var confirmAll by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    val drop = groups.sumOf { g -> g.members.count { it.mediaId !in (keeps[g.id] ?: emptySet()) } }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allButton()
                Text("枠で囲まれた写真を残します（おすすめ＝いちばん大きい写真を選んであります）。写真をタップすると、残す・残さないを変えられます。" +
                    "残さない写真はゴミ箱に入り、30日間は「ゴミ箱」から戻せます。", style = MaterialTheme.typography.bodySmall)
                Button(enabled = progress == null && drop > 0 && groups.all { (keeps[it.id] ?: emptySet()).isNotEmpty() },
                    onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(progress ?: "この画面の ${groups.size} 組：選んだ写真を残し、ほかの $drop 枚をゴミ箱へ")
                }
            }
        }
        items(groups, key = { it.id }) { g ->
            val keep = keeps[g.id] ?: emptySet()
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(8.dp)) {
                    Text(if (g.space == "shared") "共有スペース" else "あなたの個人スペース", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(g.members, key = { it.mediaId }) { m ->
                            val selected = m.mediaId in keep
                            Column(Modifier.clickable { keeps[g.id] = if (selected) keep - m.mediaId else keep + m.mediaId }) {
                                val model: Any? = hashThumb(app, m.hash, base)
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
    if (confirmAll) AlertDialog(onDismissRequest = { confirmAll = false },
        title = { Text("まとめて処理しますか？") },
        text = { Text("${groups.size} 組それぞれで、選んだ写真を残し、ほかの $drop 枚をゴミ箱に入れます。30日間は「ゴミ箱」から戻せます。") },
        confirmButton = { TextButton({
            confirmAll = false
            scope.launch {
                var ok = 0; var failed = 0
                for ((i, g) in groups.withIndex()) {
                    progress = "処理中… ${i + 1} / ${groups.size}"
                    try { if (app.net.api()!!.resolve(g.id, com.photohub.app.data.api.KeepBody((keeps[g.id] ?: emptySet()).toList())).applied) ok++ else failed++ }
                    catch (e: Exception) { failed++ }
                }
                progress = null
                info = "$ok 組を処理しました" + (if (failed > 0) "（$failed 組はできませんでした）" else "")
                com.photohub.app.data.SyncWorker.runNow(ctx)
                refresh++
            }
        }) { Text("処理する") } },
        dismissButton = { TextButton({ confirmAll = false }) { Text("やめる") } })
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
    // safety check before anyone deletes in Google Photos (which also deletes the phone's copies)
    val phoneOnly by app.db.media().phoneOnlyCounts().collectAsState(initial = emptyList())
    val unchecked by app.db.media().uncheckedCount().collectAsState(initial = 0)
    var datesPending by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { datesPending = try { app.db.media().datesToFix().size } catch (e: Exception) { 0 } }
    val updater by app.updater.state.collectAsState()
    val problems = buildList {
        val n = phoneOnly.sumOf { it.n }
        if (n > 0) add("まだSynologyに送っていない写真が、このスマホに $n 枚あります。設定の「今すぐ同期・保存」で送り終えてください")
        if (unchecked > 0) add("まだ確認していないスマホの写真が $unchecked 枚あります（同期で確認します）")
        if (datesPending > 0) add("Synology側の日付を直している途中の写真が $datesPending 枚あります。同期が終わるまで待ってください")
        if (updater is Updater.State.Available || updater is Updater.State.Ready) add("アプリに新しい版があります。先に更新してください")
    }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (problems.isEmpty()) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (problems.isEmpty()) "確認OK：このスマホの写真はすべてSynologyにあります" else "まだGoogleフォトで消さないでください",
                        style = MaterialTheme.typography.titleSmall)
                    problems.forEach { Text("・$it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Text("あなたのGoogleアカウントの分です。Synologyに保存されハッシュが一致した、撮影から一定期間たった写真・動画です。PhotoHubはGoogleから削除しません。" +
                "Googleフォトのアプリで該当する月を削除したら「削除した」を押してください。", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp))
        }
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("注意：Googleフォトのアプリで写真を消すと、このスマホの中の写真も一緒に消えます（Googleフォトの仕様です）。" +
                    "ここに出ている写真はSynologyに保存済みなので、消してもPhotoHubで見られます。スマホにも置きたい写真は、PhotoHubで選んで「このスマホに保存」で戻せます。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        if (rows.isEmpty()) item { Text("候補はまだありません（毎晩 5:10 に更新します）", Modifier.padding(8.dp)) }
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
