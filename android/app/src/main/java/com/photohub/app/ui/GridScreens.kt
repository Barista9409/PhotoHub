package com.photohub.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun AlbumsScreen(onAlbum: (Long, String) -> Unit, onFolder: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    val albums by app.db.media().albums().collectAsState(initial = emptyList())
    val folders by app.db.media().folders().collectAsState(initial = emptyList())
    if (albums.isEmpty() && folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("アルバムはまだありません") }
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
        if (albums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("アルバム（NAS）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
            }
        }
        items(albums, key = { "a${it.id}" }) { a ->
            Column(Modifier.padding(4.dp).clickable { onAlbum(a.id, a.name) }) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().background(Color(0x22888888))) {
                    val h = a.coverHash
                    if (h != null) {
                        val model: Any? = if (app.files.hasThumb(h)) app.files.thumb(h) else base?.let { "$it/api/thumb/$h" }
                        AsyncImage(model = model, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
                Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${a.count} 枚" + when (a.origin) { "google" -> "・Google"; "device" -> "・スマホのフォルダ"; else -> "" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp)) {
                    Text("このスマホのフォルダ", style = MaterialTheme.typography.titleSmall)
                    Text("「カメラ」以外は、一覧を更新したときにNASにも自分のアルバムとして記録されます",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(folders, key = { "f${it.bucket}" }) { f ->
            Column(Modifier.padding(4.dp).clickable { onFolder(f.bucket) }) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().background(Color(0x22888888))) {
                    AsyncImage(model = f.coverUri?.let { android.net.Uri.parse(it) }, contentDescription = f.bucket,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(f.bucket, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${f.count} 枚", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun FolderScreen(bucket: String, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val items by app.db.media().folder(bucket).collectAsState(initial = emptyList())
    MediaGrid(items, onOpen)
}

@Composable
fun AlbumScreen(albumId: Long, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val items by app.db.media().album(albumId).collectAsState(initial = emptyList())
    MediaGrid(items, onOpen)
}

/** Search by text (name, album, caption), year, kind and "has place" - all on-device (Room). */
@Composable
fun SearchScreen(onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var q by rememberSaveable { mutableStateOf("") }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var kind by rememberSaveable { mutableStateOf("") }
    var withPlace by rememberSaveable { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val from = year?.let { LocalDate.of(it, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
    val to = year?.let { LocalDate.of(it + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 } ?: Long.MAX_VALUE
    val results by remember(q, year, kind, withPlace) {
        app.db.media().search(q.trim(), from, to, kind, if (withPlace) 1 else 0)
    }.collectAsState(initial = emptyList())
    val thisYear = LocalDate.now().year
    MediaGrid(results, onOpen, header = {
        Column(Modifier.padding(8.dp)) {
            OutlinedTextField(q, { q = it }, label = { Text("名前・アルバム・説明") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
            LazyRowChips(listOf(null) + (thisYear downTo thisYear - 20).toList(), year, { it?.toString() ?: "全期間" }) { year = it }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "", { kind = "" }, label = { Text("すべて") })
                FilterChip(kind == "photo", { kind = "photo" }, label = { Text("写真") })
                FilterChip(kind == "video", { kind = "video" }, label = { Text("動画") })
                FilterChip(withPlace, { withPlace = !withPlace }, label = { Text("場所あり") })
            }
            Text("${results.size} 件", style = MaterialTheme.typography.bodySmall)
        }
    })
}

@Composable
private fun <T> LazyRowChips(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(values) { v -> FilterChip(v == selected, { onPick(v) }, label = { Text(label(v)) }) }
    }
}

@Composable
fun SimpleList(lines: List<Pair<String, String>>) {
    LazyColumn { items(lines) { (a, b) -> ListItem(headlineContent = { Text(a) }, supportingContent = { Text(b) }) } }
}
