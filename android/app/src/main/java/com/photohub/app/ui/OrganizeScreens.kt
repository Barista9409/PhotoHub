package com.photohub.app.ui

import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.SyncWorker
import com.photohub.app.data.api.HashesBody
import com.photohub.app.data.api.PathsBody
import com.photohub.app.data.api.TrashList
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.launch

private fun isScreenshot(it: TimelineItem) =
    it.name.contains("screenshot", ignoreCase = true) || it.name.contains("スクリーンショット")

private const val BIG_VIDEO = 200L * 1024 * 1024

/** 写真の整理: one place with cards; each shows how much there is and opens a screen to act. */
@Composable
fun OrganizeScreen(open: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val all = app.lists.timeline.collectAsState().value ?: emptyList()
    var trash by remember { mutableStateOf<TrashList?>(null) }
    var dups by remember { mutableStateOf<Int?>(null) }
    var similar by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val api = app.net.api()
        trash = try { api?.trash() } catch (e: Exception) { null }
        dups = try { api?.duplicates()?.items?.size } catch (e: Exception) { null }
        similar = try { api?.status()?.similarOpen } catch (e: Exception) { null }
    }
    val shots = remember(all) { all.filter(::isScreenshot) }
    val bigVideos = remember(all) { all.filter { it.kind == "video" && it.bytes >= BIG_VIDEO } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SectionTitle("重なっている写真")
        Group {
            SettingRow(Icons.Default.ContentCopy, "まったく同じ写真が2か所に",
                when (dups) { null -> "Synologyにつながると表示されます"; 0 -> "ありません"; else -> "$dups 件。余分な方をゴミ箱へ移せます" },
                onClick = { open(Dest.DUPLICATES.route) })
            GroupDivider()
            SettingRow(Icons.Default.AutoAwesomeMosaic, "似ている写真（連写・加工前後）",
                when (similar) { null -> "残す写真を選べます"; 0 -> "ありません"; else -> "$similar 組。残す写真を選べます" },
                onClick = { open(Dest.SIMILAR.route) })
        }
        SectionTitle("消してもよさそうな写真")
        Group {
            SettingRow(Icons.Default.Screenshot, "スクリーンショット", "${shots.size} 件・${humanBytes(shots.sumOf { it.bytes })}",
                onClick = { open("suggest/shots") })
            GroupDivider()
            SettingRow(Icons.Default.VideoFile, "大きい動画（200MB以上）", "${bigVideos.size} 本・${humanBytes(bigVideos.sumOf { it.bytes })}",
                onClick = { open("suggest/videos") })
            GroupDivider()
            SettingRow(Icons.Default.BlurOn, "ぼやけた写真", "ピントが合っていない写真（Synologyが前もって調べています）", onClick = { open("suggest/blurry") })
        }
        Text("選んで上の赤いゴミ箱を押すと、Synology・ドライブ・スマホのすべてから消えます（30日間はゴミ箱から戻せます）。写真を長押しすると選べます。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp))
        SectionTitle("日付のおかしい写真")
        Group { DateRepairRow() }
        SectionTitle("スマホの空き容量")
        Group { FreeUpCard() }
        SectionTitle("ゴミ箱")
        Group {
            SettingRow(Icons.Default.Delete, "ゴミ箱",
                trash?.let { if (it.items.isEmpty()) "空です" else "${it.items.size} 件。${it.days}日たつと自動で完全に削除されます" } ?: "消した写真を戻せます",
                onClick = { open(Dest.TRASH.route) })
        }
    }
}

/** Free up phone space: photos already on the Synology, older than the chosen period. */

@Composable
private fun FreeUpCard() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val choices = listOf(1, 3, 6, 12)
    var idx by remember { mutableIntStateOf(1) }
    var refresh by remember { mutableIntStateOf(0) }
    var freeable by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val months = choices[idx]
    LaunchedEffect(months, refresh) {
        val l = app.db.media().localOnNas(System.currentTimeMillis() - months * 30L * 24 * 3600 * 1000)
        freeable = l.size to l.sumOf { it.size }
    }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch { app.indexer.scan(); refresh++ }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.DataSaverOn, null)
            Text("  ${months}か月より前の写真をスマホから消す", style = MaterialTheme.typography.titleSmall)
        }
        Slider(idx.toFloat(), { idx = it.toInt() }, valueRange = 0f..3f, steps = 2)
        val f = freeable
        Text(when { f == null -> "計算中…"; f.first == 0 -> "消せる写真はありません（Synologyに保存済みのものだけが対象です）"
            else -> "${f.first} 枚・${humanBytes(f.second)} 空きます。Synologyにあるので、消してもこのアプリで見られます" },
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = (f?.first ?: 0) > 0 && Build.VERSION.SDK_INT >= 30, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                val uris = app.db.media().localOnNas(System.currentTimeMillis() - months * 30L * 24 * 3600 * 1000)
                    .map { android.net.Uri.parse(it.uri) }
                if (uris.isNotEmpty()) delete.launch(IntentSenderRequest.Builder(
                    MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
            }
        }) { Text("スマホから消して空きを作る") }
    }
}

/** Screenshots / big videos / blurry photos, ready to be selected and deleted. */
@Composable
fun SuggestScreen(kind: String, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val all = app.lists.timeline.collectAsState().value ?: run { Loading(); return }
    var blurry by remember { mutableStateOf<Set<String>?>(null) }
    if (kind == "blurry") LaunchedEffect(Unit) {
        blurry = try { app.net.api()?.blurry()?.items?.map { it.hash }?.toSet() ?: emptySet() } catch (e: Exception) { emptySet() }
    }
    val items = when (kind) {
        "shots" -> all.filter(::isScreenshot)
        "videos" -> all.filter { it.kind == "video" && it.bytes >= BIG_VIDEO }.sortedByDescending { it.bytes }
        else -> blurry?.let { b -> all.filter { it.hash in b } } ?: run { Loading("読み込んでいます…"); return }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Text("見つかりませんでした") }
        return
    }
    MediaGrid(items, onOpen, header = {
        Text("写真を長押しして選び、右上のゴミ箱で消せます（30日間はゴミ箱から戻せます）。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    })
}

/** Exact second copies of a photo: move the extra files to the trash folder. */
@Composable
fun DuplicatesScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val (list, err) = remote<com.photohub.app.data.api.DuplicateList> { it.duplicates() }
    var done by remember { mutableStateOf<String?>(null) }
    val items = list?.items ?: run { Text(err ?: "読み込み中…", Modifier.padding(16.dp)); return }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("同じ写真のファイルが、Synologyの別の場所にもう1つあります。残すのは1つ目（「残す」の場所）で、余分な方をPhotoHubのゴミ箱フォルダへ移します。",
                style = MaterialTheme.typography.bodySmall)
            done?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (items.isNotEmpty()) Button({
                scope.launch {
                    done = try { "${app.net.api()!!.removeDuplicates(PathsBody(items.map { it.path }))["removed"] ?: 0} 件を移しました" }
                           catch (e: Exception) { "移せませんでした（${e.message}）" }
                }
            }, Modifier.fillMaxWidth()) { Text("余分な ${items.size} 件（${humanBytes(items.sumOf { it.bytes })}）をまとめて移す") }
        }
        LazyColumn {
            items(items, key = { it.path }) { d ->
                Column(Modifier.padding(16.dp, 8.dp)) {
                    Text(d.path.substringAfter("/data/"), style = MaterialTheme.typography.bodyMedium)
                    Text("残す：" + d.keep.substringAfter("/data/") + "・" + humanBytes(d.bytes), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Trash: everything deleted in the last 30 days; restore or delete for good. */
@Composable
fun TrashScreen() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    val base by UiState.base.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<TrashList?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmEmpty by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(reload) {
        data = try { app.net.api()?.trash() } catch (e: Exception) { err = "Synologyにつながりません"; null }
        if (data == null && err == null) err = "Synologyにつながりません"
    }
    val d = data ?: run { if (err != null) Text(err!!, Modifier.padding(16.dp)) else Loading(); return }
    fun act(block: suspend () -> Unit) = scope.launch {
        try { block() } catch (e: Exception) { err = e.message }
        selected = emptySet(); reload++; SyncWorker.runNow(app)
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (d.items.isEmpty()) "ゴミ箱は空です" else "消した写真は ${d.days} 日間ここに残り、そのあと自動で完全に削除されます。家族の共有の写真は、家族みんなのゴミ箱に出ます。",
                style = MaterialTheme.typography.bodySmall)
            if (d.items.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = selected.isNotEmpty(), onClick = { act { app.net.api()!!.restore(HashesBody(selected.toList())) } }) {
                    Text(if (selected.isEmpty()) "元に戻す" else "${selected.size} 件を元に戻す")
                }
                OutlinedButton({ confirmEmpty = if (selected.isEmpty()) d.items.map { it.hash } else selected.toList() }) {
                    Text(if (selected.isEmpty()) "ゴミ箱を空にする" else "完全に削除")
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize()) {
            items(d.items, key = { it.hash }) { t ->
                val on = t.hash in selected
                Box(Modifier.padding(1.dp).aspectRatio(1f).background(Color(0x22888888))
                    .clickable { selected = if (on) selected - t.hash else selected + t.hash }) {
                    val model: Any? = hashThumb(app, t.hash, base)
                    AsyncImage(model, t.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Text("あと${t.daysLeft}日", color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp))
                    Icon(if (on) Icons.Default.CheckCircle else Icons.Outlined.Circle, null,
                        tint = if (on) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                            .background(if (on) Color.White else Color.Transparent, CircleShape))
                }
            }
        }
    }
    confirmEmpty?.let { list ->
        AlertDialog(onDismissRequest = { confirmEmpty = null }, title = { Text("${list.size} 件を完全に削除") },
            text = { Text("完全に削除すると、元に戻せません。") },
            confirmButton = { TextButton({ confirmEmpty = null; act { app.net.api()!!.emptyTrash(HashesBody(list)) } }) { Text("完全に削除") } },
            dismissButton = { TextButton({ confirmEmpty = null }) { Text("やめる") } })
    }
}
