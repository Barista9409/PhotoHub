package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.AlbumWithCount
import kotlinx.coroutines.launch

/** One kind of album (人物, 旅行, お出かけ ...): a tile on the album home, a page of its own. */
data class AlbumGroup(
    val key: String,
    val title: String,
    val sub: String,
    val albums: List<AlbumWithCount>,
    /** albums named after a date (旅行, お出かけ, 日常, ふりかえり): year headings and year buttons */
    val dated: Boolean = false,
    /** how the page starts: "date", "count" or "name" */
    val sort: String = "name",
)

/** 「2024年8月10日 …」「2024年8月の日常」 -> 20240810 / 20240800 (0 when there is no date). */
fun albumDate(name: String): Long = Regex("(\\d{4})年(\\d{1,2})月(?:(\\d{1,2})日)?").find(name)?.groupValues
    ?.let { g -> g[1].toLong() * 10000 + g[2].toLong() * 100 + (g[3].toLongOrNull() ?: 0) } ?: 0

private val AUTO = setOf("digest", "trip", "folder", "person", "place", "growth", "favorite", "event", "daily", "kind")

/**
 * The album groups of one side: マイフォト ([family] false: the person's own albums) or ファミリー (true:
 * the family's). [phoneFolders]: phone folders shown as folders, so their Synology album is left out.
 */
fun albumGroups(albums: List<AlbumWithCount>, family: Boolean, phoneFolders: Set<String> = emptySet()): List<AlbumGroup> {
    fun of(origin: String) = albums.filter { it.origin == origin }
    val growth = of("growth").sortedWith(compareBy({ it.name.substringBeforeLast(' ') },
        { it.name.substringAfterLast(' ').removeSuffix("歳").toIntOrNull() ?: 0 }))
    val own = albums.filter { it.origin !in AUTO && !(it.origin == "device" && it.name in phoneFolders) }
    return listOf(
        AlbumGroup("fav", if (family) "家族のお気に入り" else "お気に入り", "♡を付けた写真", of("favorite")),
        AlbumGroup("person", "人物", "Synology Photosが見分けた人ごと", of("person"), sort = "count"),
        AlbumGroup("growth", "成長", "人物アルバムの誕生日から、年齢ごと", growth),
        AlbumGroup("trip", "旅行", "家から離れた場所で撮った日々", of("trip"), dated = true, sort = "date"),
        AlbumGroup("event", "お出かけ・イベント", "たくさん撮った日を1日ずつ", of("event"), dated = true, sort = "date"),
        AlbumGroup("daily", "日常", "ほかに入らない毎日の写真を月ごとに", of("daily"), dated = true, sort = "date"),
        AlbumGroup("digest", "ふりかえり", "毎月のいい写真", of("digest"), dated = true, sort = "date"),
        AlbumGroup("place", "場所", "写真をたくさん撮った町ごと", of("place"), sort = "count"),
        AlbumGroup("own", if (family) "家族のアルバム" else "自分のアルバム",
            if (family) "家族みんなが見られるアルバム" else "自分で作ったアルバム", own),
        AlbumGroup("folder", "共有フォルダ", "家族の共有フォルダを、そのままアルバムに", of("folder")),
        AlbumGroup("kind", "種類", "スクリーンショットなど", of("kind")),
    )
}

/** Which album stays when two hold mostly the same photos: one people made, then the most specific. */
private fun keepRank(origin: String) = when (origin) {
    "favorite", "folder" -> 1; "trip" -> 2; "event" -> 3; "person", "growth" -> 4; "daily" -> 5; "digest" -> 6
    "place" -> 7; "kind" -> 8; else -> 0
}

/**
 * 「最近のアルバム」 without look-alikes: of albums that share most of their photos (旅行「2026年11月 岐阜県」,
 * 場所「岐阜県 岐阜市」, an album made by hand of the same days...) only one is shown, and each album shown
 * gets a cover no other one uses.
 */
suspend fun distinctRecent(app: PhotoHubApp, byNewest: List<AlbumWithCount>, n: Int = 12): List<AlbumWithCount> {
    val pool = byNewest.take(n * 4)
    if (pool.isEmpty()) return emptyList()
    val members = app.db.media().albumMembers(pool.map { it.id }).groupBy({ it.albumId }, { it.hash })
    val kept = mutableListOf<AlbumWithCount>()
    val keptSets = mutableListOf<Set<String>>()
    for (a in pool.sortedWith(compareBy<AlbumWithCount> { keepRank(it.origin) }.thenByDescending { it.latest ?: 0 })) {
        val mine = members[a.id].orEmpty().toSet()
        if (mine.isEmpty()) continue
        val lookAlike = keptSets.any { other ->
            val shared = mine.count { it in other }
            shared >= 0.6 * minOf(mine.size, other.size)
        }
        if (lookAlike) continue
        kept += a; keptSets += mine
        if (kept.size >= n) break
    }
    return withDistinctCovers(kept.sortedByDescending { it.latest ?: 0 }, members)
}

/** Each album its own cover: the newest photo no album before it already shows. */
fun withDistinctCovers(albums: List<AlbumWithCount>, members: Map<Long, List<String>>): List<AlbumWithCount> {
    val used = HashSet<String>()
    return albums.map { a ->
        val pick = members[a.id].orEmpty().firstOrNull { it !in used } ?: a.coverHash
        if (pick != null) used += pick
        if (pick == a.coverHash) a else a.copy(coverHash = pick)
    }
}

/** What a group page shows, in its order (the selection bar's すべて選択 uses the same list). */
fun AlbumGroup.ordered(sort: String = this.sort, query: String = ""): List<AlbumWithCount> {
    val q = query.trim()
    val list = if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
    return when (sort) {
        "date" -> list.sortedWith(compareByDescending<AlbumWithCount> { albumDate(it.name) }.thenByDescending { it.id })
        "count" -> list.sortedByDescending { it.count }
        else -> list.sortedBy { it.name }
    }
}

/**
 * The album home: 「最近のアルバム」 (one row), then one tile per group (2 across, a collage of its
 * albums). A tile opens the group's page: its albums 3 across, newest first with a heading per year
 * (and year buttons to jump), or by size / name, with a name filter. [openKey] is the open group
 * (null = the home), kept by the caller so coming back from an album keeps the page.
 */
@Composable
fun AlbumGroupsView(
    groups: List<AlbumGroup>,
    openKey: String?,
    onOpenGroup: (String?) -> Unit,
    card: (AlbumWithCount) -> @Composable () -> Unit,
    header: @Composable () -> Unit = {},
    recent: List<AlbumWithCount> = emptyList(),
    extraTiles: @Composable (tileModifier: Modifier) -> Unit = {},
) {
    val open = groups.firstOrNull { it.key == openKey }
    androidx.activity.compose.BackHandler(enabled = openKey != null) { onOpenGroup(null) }
    if (open != null) { key(open.key) { GroupPage(open, onBack = { onOpenGroup(null) }, card = card) }; return }
    val shown = groups.filter { it.albums.isNotEmpty() }
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val recentShown by androidx.compose.runtime.produceState(recent, recent) { value = distinctRecent(app, recent) }
    // the tiles' collages: four different photos, not the same newest one four times
    val tileCovers by androidx.compose.runtime.produceState(emptyMap<String, List<String>>(), shown) {
        val firsts = shown.associate { g -> g.key to g.ordered().take(6) }
        val members = app.db.media().albumMembers(firsts.values.flatten().map { it.id }.distinct()).groupBy({ it.albumId }, { it.hash })
        value = firsts.mapValues { (_, list) -> withDistinctCovers(list, members).mapNotNull { it.coverHash }.distinct().take(4) }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { com.photohub.app.data.Pins.load(app) }
    val pinIds by com.photohub.app.data.Pins.ids.collectAsState()
    // pinned albums of this side, in the order they were pinned
    val pinned = remember(pinIds, groups) {
        val byId = groups.flatMap { it.albums }.associateBy { it.id }
        pinIds.mapNotNull { byId[it] }
    }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(4.dp), state = rememberLazyGridState()) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "header") { header() }
        if (pinned.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "pins") {
                Text("ピン止め", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp, 12.dp, 8.dp, 4.dp))
            }
            items(pinned.size, key = { "p${pinned[it].id}" }) { i -> card(pinned[i])() }
        }
        if (recentShown.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "recent") {
            Column {
                Text("最近のアルバム", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp, 12.dp, 8.dp, 4.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 4.dp)) {
                    items(recentShown, key = { "r${it.id}" }) { a -> Box(Modifier.width(130.dp)) { card(a)() } }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "kinds") {
            Text("種類ごと", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp))
        }
        items(shown.size, key = { "g${shown[it].key}" }) { i ->
            val g = shown[i]
            val covers = tileCovers[g.key] ?: remember(g) { g.ordered().mapNotNull { a -> a.coverHash }.distinct().take(4) }
            GroupTile(g.title, "${g.albums.size} 件", covers) { onOpenGroup(g.key) }
        }
        item(key = "extra") { extraTiles(Modifier) }
    }
}

/** A group's door: up to 4 covers as a collage, its name and how many albums. */
@Composable
fun GroupTile(title: String, sub: String, covers: List<String>, coverUris: List<String> = emptyList(), onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Column(Modifier.padding(6.dp).clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x22888888))) {
            val models: List<Any?> = covers.map { h -> hashThumb(app, h, base) } + coverUris.map { android.net.Uri.parse(it) }
            if (models.size <= 1) models.firstOrNull()?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // a 2 x 2 collage of the newest albums' covers
                for (r in 0 until 2) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (c in 0 until 2) Box(Modifier.weight(1f).fillMaxSize().background(Color(0x11888888))) {
                        models.getOrNull(r * 2 + c)?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A group's page: back, sort, name filter, year headings with year buttons, albums 3 across. */
@Composable
private fun GroupPage(g: AlbumGroup, onBack: () -> Unit, card: (AlbumWithCount) -> @Composable () -> Unit) {
    var sort by rememberSaveable { mutableStateOf(g.sort) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val list = remember(g, sort, query) { g.ordered(sort, query) }
    // newest first in a dated group: a heading per year
    val byYear = g.dated && sort == "date"
    val rows: List<Any> = remember(list, byYear) {
        if (!byYear) list else buildList {
            var year = -1L
            list.forEach { a ->
                val y = albumDate(a.name) / 10000
                if (y != year) { add(y); year = y }
                add(a)
            }
        }
    }
    val years = remember(rows) { rows.filterIsInstance<Long>() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "アルバムの一覧へ") }
            Column(Modifier.weight(1f)) {
                Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text("${g.albums.size} 件" + if (g.sub.isNotEmpty()) "・${g.sub}" else "", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton({ searching = !searching; if (!searching) query = "" }) { Icon(Icons.Default.Search, "名前で絞り込む") }
        }
        if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("アルバムの名前（例：糸魚川）") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val sorts = listOfNotNull(if (g.dated) "date" to "新しい順" else null, "count" to "枚数の多い順", "name" to "名前順")
            items(sorts, key = { it.first }) { (k, label) -> FilterChip(sort == k, { sort = k }, label = { Text(label) }) }
            if (years.size > 1) items(years, key = { "y$it" }) { y ->
                // jump to that year
                FilterChip(false, { scope.launch { grid.scrollToItem(rows.indexOf(y)) } }, label = { Text(if (y == 0L) "日付なし" else "${y}年") })
            }
        }
        if (list.isEmpty()) { Text("見つかりませんでした", Modifier.padding(24.dp)); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.fillMaxSize().padding(horizontal = 4.dp), state = grid) {
            items(rows.size, key = { i -> rows[i].let { if (it is Long) "y$it" else "a${(it as AlbumWithCount).id}" } },
                span = { i -> if (rows[i] is Long) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { i ->
                when (val r = rows[i]) {
                    is Long -> Text(if (r == 0L) "日付なし" else "${r}年", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp))
                    is AlbumWithCount -> card(r)()
                }
            }
        }
    }
}
