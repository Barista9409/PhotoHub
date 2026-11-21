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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    val albums by app.db.media().albums().collectAsState(initial = emptyList())
    val folders by app.db.media().folders().collectAsState(initial = emptyList())
    if (albums.isEmpty() && folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("アルバムはまだありません") }
        return
    }
    val mine = albums.filter { !it.shared }
    val family = albums.filter { it.shared }
    LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
        if (mine.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHead("自分のアルバム", "Synologyにある、自分だけのアルバム") }
            items(mine, key = { "a${it.id}" }) { a -> AlbumCard(a) { onAlbum(a.id, a.name) } }
        }
        if (family.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHead("家族の共有アルバム", "家族みんなが見られるアルバム（「共有」タブにもあります）") }
            items(family, key = { "a${it.id}" }) { a -> AlbumCard(a) { onAlbum(a.id, a.name) } }
        }
        if (folders.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHead("このスマホのフォルダ", "「カメラ」以外は、同期したときにSynologyにも自分のアルバムとして記録されます")
            }
        }
        items(folders, key = { "f${it.bucket}" }) { f ->
            Column(Modifier.padding(4.dp).clickable { onFolder(f.bucket) }) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
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
private fun SectionHead(title: String, sub: String) {
    Column(Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AlbumCard(a: com.photohub.app.data.db.AlbumWithCount, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Column(modifier.padding(4.dp).clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
            val h = a.coverHash
            if (h != null) {
                val model: Any? = if (app.files.hasThumb(h)) app.files.thumb(h) else base?.let { "$it/api/thumb/$h" }
                AsyncImage(model = model, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("${a.count} 枚" + when (a.origin) { "google" -> "・Googleフォトから"; "device" -> "・スマホのフォルダ"; else -> "" },
            style = MaterialTheme.typography.bodySmall)
    }
}

/** 共有: what the family shares - shared albums on top, then every photo in the shared space. */
@Composable
fun SharedScreen(onAlbum: (Long, String) -> Unit, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val albums by app.db.media().albums().collectAsState(initial = emptyList())
    val family = albums.filter { it.shared }
    val items by app.db.media().sharedSpace().collectAsState(initial = emptyList())
    if (family.isEmpty() && items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("家族の共有スペースに写真はまだありません。\nSynology Photosの「共有スペース」に入れた写真と、家族で共有したアルバムがここに出ます。")
        }
        return
    }
    MediaGrid(items, onOpen, zoomBar = true, header = {
        Column {
            if (family.isNotEmpty()) {
                SectionHead("家族の共有アルバム", "${family.size} 件")
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    items(family, key = { it.id }) { a -> AlbumCard(a, Modifier.width(140.dp)) { onAlbum(a.id, a.name) } }
                }
            }
            SectionHead("共有スペースのすべての写真", "${items.size} 件")
        }
    })
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

/** What the search box understands: words, a year ("2023"), a month ("2023年8月", "2023/8"). */
private data class Query(val text: String, val year: Int?, val month: Int?)

private fun parse(q: String): Query {
    val m = Regex("""(19|20)\d{2}(?:\s*[年/\-.]\s*(\d{1,2})\s*月?)?\s*年?""").find(q)
    val year = m?.value?.take(4)?.toIntOrNull()
    val month = m?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..12 }
    val text = (if (m != null) q.removeRange(m.range) else q).trim()
    return Query(text, year, month)
}

/**
 * Search: a start page with ways to look (kinds, years, albums), then results.
 * Everything runs on the phone (works offline too).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpen: (List<TimelineItem>, Int) -> Unit, onAlbum: (Long, String) -> Unit = { _, _ -> }) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var q by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    var withPlace by rememberSaveable { mutableStateOf(false) }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var shots by rememberSaveable { mutableStateOf(false) }
    val parsed = remember(q) { parse(q) }
    val active = q.isNotBlank() || kind != "" || withPlace || favOnly || shots
    val zone = ZoneId.systemDefault()
    val from = parsed.year?.let { y -> LocalDate.of(y, parsed.month ?: 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
    val to = parsed.year?.let { y ->
        val start = LocalDate.of(y, parsed.month ?: 1, 1)
        (if (parsed.month != null) start.plusMonths(1) else start.plusYears(1)).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    } ?: Long.MAX_VALUE
    val raw by remember(parsed, kind, withPlace) {
        app.db.media().search(parsed.text, from, to, kind, if (withPlace) 1 else 0)
    }.collectAsState(initial = emptyList())
    val favs by app.prefs.favorites.collectAsState(initial = emptySet())
    val results = remember(raw, favOnly, shots, favs) {
        raw.filter { (!favOnly || (it.hash != null && it.hash in favs)) && (!shots || it.name.contains("screenshot", ignoreCase = true)) }
    }
    val all by app.db.media().timeline().collectAsState(initial = emptyList())
    val years = remember(all) {
        all.groupBy { java.time.Instant.ofEpochMilli(it.takenAt).atZone(zoneFor(it.tz)).year }.toSortedMap(compareByDescending { it })
    }
    val albums by app.db.media().albums().collectAsState(initial = emptyList())

    val box: @Composable () -> Unit = {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, placeholder = { Text("例：海、IMG_1234、2023、2023年8月") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (active) androidx.compose.material3.IconButton({ q = ""; kind = ""; withPlace = false; favOnly = false; shots = false }) {
                    Icon(Icons.Default.Close, "条件を消す") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "video", { kind = if (kind == "video") "" else "video" }, label = { Text("動画") })
                FilterChip(kind == "photo", { kind = if (kind == "photo") "" else "photo" }, label = { Text("写真だけ") })
                FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("お気に入り") })
                FilterChip(withPlace, { withPlace = !withPlace }, label = { Text("場所あり") })
                FilterChip(shots, { shots = !shots }, label = { Text("スクリーンショット") })
            }
            if (active) {
                val cond = buildList {
                    parsed.year?.let { add(if (parsed.month != null) "${it}年${parsed.month}月" else "${it}年") }
                    if (parsed.text.isNotBlank()) add("「${parsed.text}」（ファイル名・アルバム名・説明）")
                }
                Text((if (cond.isEmpty()) "" else cond.joinToString("・") + "　") + "${results.size} 件",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (active) { MediaGrid(results, onOpen, header = box); return }

    // start page: ways to look
    LazyColumn(Modifier.fillMaxSize()) {
        item { box() }
        item {
            Text("ことばは、ファイル名・アルバム名・写真の説明から探します。年や「2023年8月」も入れられます。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (years.isNotEmpty()) {
            item { Text("年から探す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(years.entries.toList(), key = { it.key }) { (y, list) ->
                        CoverTile("${y}年", "${list.size} 件", list.first()) { q = "${y}年" }
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { Text("アルバムから探す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    items(albums, key = { it.id }) { a -> AlbumCard(a, Modifier.width(130.dp)) { onAlbum(a.id, a.name) } }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(title: String, sub: String, cover: TimelineItem, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Box(Modifier.width(120.dp).aspectRatio(0.8f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(model = thumbModel(app, cover, base), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x44000000)))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
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
