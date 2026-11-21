package com.photohub.app.ui

import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private val COLUMNS = listOf(3, 4, 5, 7)

private fun localDate(it: TimelineItem): LocalDate = Instant.ofEpochMilli(it.takenAt).atZone(zoneFor(it.tz)).toLocalDate()

/** "今日" / "昨日" / "11月21日(土)" / "2023年11月21日(火)" */
fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String {
    val w = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)
    return when {
        d == today -> "今日"
        d == today.minusDays(1) -> "昨日"
        d.year == today.year -> "${d.monthValue}月${d.dayOfMonth}日($w)"
        else -> "${d.year}年${d.monthValue}月${d.dayOfMonth}日($w)"
    }
}

/**
 * The photo grid used everywhere (timeline, albums, folders, search).
 *  - pinch to change the size (3 / 4 / 5 / 7 per row; 7 groups by month)
 *  - drag the handle on the right edge to jump through the years
 *  - long-press to select; then share, favourite or delete from the phone
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(items: List<TimelineItem>, onOpen: (List<TimelineItem>, Int) -> Unit, header: (@Composable () -> Unit)? = null) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    val favs by app.prefs.favorites.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var colIdx by rememberSaveable { mutableStateOf(1) }
    val cols = COLUMNS[colIdx]
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    if (items.isEmpty() && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("写真がありません。NASにログインして同期するか、写真へのアクセスを許可してください。",
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    val byMonth = cols >= 7
    val rows = remember(items, byMonth) {
        val out = mutableListOf<Any>()
        var last: Any? = null
        val today = LocalDate.now()
        items.forEachIndexed { i, it ->
            val d = localDate(it)
            val key: Any = if (byMonth) d.withDayOfMonth(1) else d
            if (key != last) {
                out += if (byMonth) Header("${d.year}年${d.monthValue}月", key.toString()) else Header(dayLabel(d, today), key.toString())
                last = key
            }
            out += i
        }
        out
    }
    val grid = rememberLazyGridState()
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        selected = emptySet()
        scope.launch { app.indexer.scan() }
    }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols), state = grid,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                // two-finger pinch changes the grid size; one finger scrolls as usual
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var acc = 1f
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.size >= 2) {
                            acc *= e.calculateZoom()
                            if (acc > 1.35f && colIdx > 0) { colIdx--; acc = 1f }
                            if (acc < 0.75f && colIdx < COLUMNS.lastIndex) { colIdx++; acc = 1f }
                            e.changes.forEach { it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(rows, key = { r -> if (r is Header) "h:${r.key}" else items[r as Int].itemKey },
                span = { r -> if (r is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { r ->
                if (r is Header) {
                    Text(r.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
                } else {
                    val idx = r as Int
                    val it = items[idx]
                    val isSel = it.itemKey in selected
                    Box(Modifier.aspectRatio(1f).background(Color(0x22888888))
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - it.itemKey else selected + it.itemKey
                                else onOpen(items, idx)
                            },
                            onLongClick = { selected = selected + it.itemKey },
                        )) {
                        AsyncImage(model = thumbModel(app, it, base), contentDescription = it.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().then(if (isSel) Modifier.padding(10.dp).clip(RoundedCornerShape(6.dp)) else Modifier))
                        if (it.kind == "video") {
                            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                it.duration?.let { d -> Text("%d:%02d".format((d / 60).toInt(), (d % 60).toInt()), color = Color.White, fontSize = 10.sp) }
                                Icon(Icons.Default.PlayCircle, null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (it.hash != null && it.hash in favs) {
                            Icon(Icons.Default.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(14.dp))
                        }
                        if (cols <= 5) StoreBadges(it.stores, Modifier.align(Alignment.BottomStart).padding(2.dp))
                        if (selecting) {
                            Icon(if (isSel) Icons.Default.CheckCircle else Icons.Outlined.Circle, null,
                                tint = if (isSel) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp)
                                    .background(if (isSel) Color.White else Color.Transparent, CircleShape))
                        }
                    }
                }
            }
        }

        // fast scroller with a date bubble
        if (rows.size > 60) FastScroller(grid, rows, items, Modifier.align(Alignment.CenterEnd))

        // selection bar
        AnimatedVisibility(selecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, "選択をやめる") }
                    Text("${selected.size} 件を選択", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({
                        val chosen = items.filter { it.itemKey in selected }
                        scope.launch {
                            busy = "準備中…"
                            val uris = chosen.mapNotNull { fetchOriginal(ctx, app, it, base) }
                            busy = null
                            if (uris.isNotEmpty()) {
                                val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = if (chosen.all { it.kind == "photo" }) "image/*" else "*/*"
                                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                                    else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "共有"))
                            }
                        }
                    }) { Icon(Icons.Default.Share, "共有") }
                    IconButton({
                        scope.launch { items.filter { it.itemKey in selected && it.hash != null }.forEach { app.prefs.toggleFavorite(it.hash!!) } }
                        selected = emptySet()
                    }) { Icon(Icons.Default.Favorite, "お気に入り") }
                    IconButton({ confirmDelete = items.filter { it.itemKey in selected && it.localUri != null } }) {
                        Icon(Icons.Default.Delete, "スマホから削除")
                    }
                }
            }
        }
        busy?.let { Text(it, Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(16.dp), color = Color.White) }
    }

    confirmDelete?.let { list ->
        val unsafe = list.count { "nas" !in it.stores && "drive" !in it.stores }
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("スマホから ${list.size} 件を削除") },
            text = {
                Text(if (list.isEmpty()) "スマホにある写真が選ばれていません。"
                else if (unsafe > 0) "うち $unsafe 件はまだNASにもドライブにもありません。削除すると元に戻せません。"
                else "NASやドライブにある写真なので、削除してもこのアプリで見られます。")
            },
            confirmButton = {
                TextButton(enabled = list.isNotEmpty() && Build.VERSION.SDK_INT >= 30, onClick = {
                    confirmDelete = null
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, list.map { android.net.Uri.parse(it.localUri) })
                    deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }) { Text("削除") }
            },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("やめる") } })
    }
}

private data class Header(val label: String, val key: String)

@Composable
private fun FastScroller(grid: androidx.compose.foundation.lazy.grid.LazyGridState, rows: List<Any>, items: List<TimelineItem>,
                         modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var frac by remember { mutableFloatStateOf(0f) }
    val shown by remember { derivedStateOf { if (dragging) frac else grid.firstVisibleItemIndex.toFloat() / rows.size.coerceAtLeast(1) } }
    BoxWithConstraints(modifier.fillMaxHeight().width(48.dp)) {
        val h = constraints.maxHeight.toFloat()
        val thumbPx = with(LocalDensity.current) { 44.dp.toPx() }
        val y = (shown * (h - thumbPx)).coerceIn(0f, h - thumbPx)
        Box(Modifier.fillMaxSize().pointerInput(rows.size) {
            detectVerticalDragGestures(
                onDragStart = { o -> dragging = true; frac = (o.y / h).coerceIn(0f, 1f) },
                onDragEnd = { dragging = false }, onDragCancel = { dragging = false },
            ) { change, _ ->
                frac = (change.position.y / h).coerceIn(0f, 1f)
                scope.launch { grid.scrollToItem((frac * (rows.size - 1)).roundToInt()) }
            }
        }) {
            Box(Modifier.offset { IntOffset(0, y.roundToInt()) }.align(Alignment.TopEnd).padding(end = 2.dp)
                .size(width = 8.dp, height = 44.dp).background(Color(0x99888888), RoundedCornerShape(4.dp)))
            if (dragging) {
                val r = rows.getOrNull((frac * (rows.size - 1)).roundToInt())
                val idx = (r as? Int) ?: rows.drop((frac * (rows.size - 1)).roundToInt()).firstOrNull { it is Int } as? Int
                idx?.let { i ->
                    val d = localDate(items[i])
                    Text("${d.year}年${d.monthValue}月", color = Color.White,
                        modifier = Modifier.offset { IntOffset(-300, y.roundToInt()) }
                            .background(Color(0xDD1E3A5F), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Timeline with "◯年前の今日" at the top. */
@Composable
fun TimelineScreen(onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    val items by app.db.media().timeline().collectAsState(initial = emptyList())
    val today = LocalDate.now()
    val memories = remember(items) {
        items.filter { val d = localDate(it); d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year }
            .groupBy { today.year - localDate(it).year }.toSortedMap()
    }
    MediaGrid(items, onOpen, header = if (memories.isEmpty()) null else ({
        LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Box(Modifier.width(4.dp)) }
            items(memories.entries.toList(), key = { it.key }) { (years, list) ->
                Box(Modifier.size(width = 120.dp, height = 160.dp).clip(RoundedCornerShape(12.dp))
                    .combinedClickableCompat { onOpen(list, 0) }) {
                    AsyncImage(model = previewModel(app, list.first(), base), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Color(0x33000000)))
                    Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        Text("${years}年前の今日", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("${list.size}枚", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }))
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)
