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
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.FamilyRestroom
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

/** Grid sizes: (columns, grouping). Pinch moves between them; the 日/月/年 switch picks one. */
private data class Level(val cols: Int, val group: String)
private val LEVELS = listOf(Level(3, "day"), Level(4, "day"), Level(6, "month"), Level(9, "year"))
private const val DAY_LEVEL = 1

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
fun MediaGrid(allItems: List<TimelineItem>, onOpen: (List<TimelineItem>, Int) -> Unit, header: (@Composable () -> Unit)? = null,
              zoomBar: Boolean = false, albumId: Long? = null) {
    var kindFilter by rememberSaveable { mutableStateOf("") }   // "" / photo / video
    val items = remember(allItems, kindFilter) { if (kindFilter == "") allItems else allItems.filter { it.kind == kindFilter } }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    val favs by app.prefs.favorites.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    var colIdx by rememberSaveable { mutableStateOf(DAY_LEVEL) }
    val level = LEVELS[colIdx]
    val cols = level.cols
    var jumpTo by remember { mutableStateOf<Int?>(null) }   // item index to show after switching the level
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var moveOf by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var albumOf by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    if (allItems.isEmpty() && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("写真がありません。写真へのアクセスを許可するか、右上のアイコン →「このスマホ」→「今すぐ一覧を更新」を押してください。",
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    val rows = remember(items, level.group) {
        val out = mutableListOf<Any>()
        var last: Any? = null
        val today = LocalDate.now()
        items.forEachIndexed { i, it ->
            val d = localDate(it)
            val key: Any = when (level.group) { "year" -> d.year; "month" -> d.withDayOfMonth(1); else -> d }
            if (key != last) {
                out += when (level.group) {
                    "year" -> Header("${d.year}年", "y$key", i)
                    "month" -> Header("${d.year}年${d.monthValue}月", "m$key", i)
                    else -> Header(dayLabel(d, today), "d$key", i)
                }
                last = key
            }
            out += i
        }
        out
    }
    val grid = rememberLazyGridState()
    val headerOffset = if (header != null) 1 else 0
    androidx.compose.runtime.LaunchedEffect(rows) {
        jumpTo?.let { target ->
            val r = rows.indexOfFirst { it == target || (it is Header && it.first == target) }
            if (r >= 0) grid.scrollToItem(r + headerOffset)
            jumpTo = null
        }
    }
    // keep the place when the level changes (pinch or switch)
    fun setLevel(i: Int, at: Int? = null) {
        if (i == colIdx) { at?.let { t -> scope.launch { rows.indexOfFirst { it == t }.takeIf { it >= 0 }?.let { grid.scrollToItem(it + headerOffset) } } }; return }
        jumpTo = at ?: (rows.getOrNull((grid.firstVisibleItemIndex - headerOffset).coerceAtLeast(0))
            ?.let { r -> if (r is Header) r.first else r as Int })
        colIdx = i
    }
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        selected = emptySet()
        scope.launch { app.indexer.scan() }
    }

    Column(Modifier.fillMaxSize()) {
    if (zoomBar) {
        // 年 / 月 / 日 and 写真 / 動画: always at the top, never over the photos
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pills(listOf("年" to 3, "月" to 2, "日" to DAY_LEVEL), if (colIdx <= DAY_LEVEL) DAY_LEVEL else colIdx) { setLevel(it) }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Pills(listOf("すべて" to "", "写真" to "photo", "動画" to "video"), kindFilter) { kindFilter = it }
        }
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
                            if (acc > 1.35f && colIdx > 0) { setLevel(colIdx - 1); acc = 1f }
                            if (acc < 0.75f && colIdx < LEVELS.lastIndex) { setLevel(colIdx + 1); acc = 1f }
                            e.changes.forEach { it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                }
            },
            horizontalArrangement = Arrangement.spacedBy(if (cols >= 6) 1.dp else 2.dp),
            verticalArrangement = Arrangement.spacedBy(if (cols >= 6) 1.dp else 2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            if (header != null) item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(rows, key = { r -> if (r is Header) "h:${r.key}" else items[r as Int].itemKey },
                span = { r -> if (r is Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { r ->
                if (r is Header) {
                    // month / year headers are big and tappable: they open the finer view at that point
                    val big = level.group != "day"
                    Row(Modifier.fillMaxWidth().then(if (big) Modifier.combinedClickable(onClick = {
                        setLevel(if (level.group == "year") 2 else DAY_LEVEL, r.first)
                    }) else Modifier).padding(start = 12.dp, end = 12.dp, top = if (big) 20.dp else 16.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(r.label, style = if (big) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            else MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                        if (big) Text("開く ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    val idx = r as Int
                    val it = items[idx]
                    val isSel = it.itemKey in selected
                    Box(Modifier.aspectRatio(1f).background(Color(0x22888888))
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - it.itemKey else selected + it.itemKey
                                else if (level.group != "day") setLevel(DAY_LEVEL, idx)   // like Google Photos: zoom into that day
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
        androidx.compose.animation.AnimatedVisibility(visible = selecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
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
                    IconButton({ albumOf = items.filter { it.itemKey in selected && it.hash != null } }) {
                        Icon(Icons.Default.PhotoAlbum, "アルバムに追加")
                    }
                    if (albumId != null) IconButton({
                        val chosen = items.filter { it.itemKey in selected && it.hash != null }.map { it.hash!! }
                        selected = emptySet()
                        scope.launch {
                            toast = try { app.net.api()!!.removeFromAlbum(albumId, com.photohub.app.data.api.HashesBody(chosen)); "アルバムから外しました" }
                                    catch (e: Exception) { "外せませんでした（${e.message}）" }
                            com.photohub.app.data.SyncWorker.runNow(ctx)
                        }
                    }) { Icon(Icons.Default.RemoveCircleOutline, "アルバムから外す") }
                    IconButton({ moveOf = items.filter { it.itemKey in selected && it.hash != null } }) {
                        Icon(Icons.Default.FamilyRestroom, "自分だけ／家族で共有へ移す")
                    }
                    IconButton({ confirmDelete = items.filter { it.itemKey in selected } }) {
                        Icon(Icons.Default.Delete, "削除")
                    }
                }
            }
        }
        busy?.let { Text(it, Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(16.dp), color = Color.White) }
    }
    }

    confirmDelete?.let { list ->
        val phoneOnly = list.count { it.hash == null && it.driveId == null }
        val onPhone = list.filter { it.localUri != null }
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("${list.size} 件を削除") },
            text = {
                Text("Synology・Googleドライブ・このスマホの、すべてから消します。Synologyの写真は30日間ゴミ箱に残るので、「写真の整理」→「ゴミ箱」から戻せます。" +
                    (if (phoneOnly > 0) "\n\nうち $phoneOnly 件はこのスマホにしかありません。ゴミ箱に入らず、元に戻せません。" else ""))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    selected = emptySet()
                    scope.launch {
                        busy = "削除しています…"
                        val o = com.photohub.app.data.Deleter.everywhere(app, list)
                        busy = null
                        toast = o.error ?: "ゴミ箱に ${o.trashed} 件" + (if (o.fromDrive > 0) "・ドライブから ${o.fromDrive} 件" else "")
                        if (o.phoneUris.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, o.phoneUris)
                            deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        }
                        com.photohub.app.data.SyncWorker.runNow(ctx)
                    }
                }) { Text("すべてから削除") }
            },
            dismissButton = {
                Row {
                    if (onPhone.isNotEmpty() && Build.VERSION.SDK_INT >= 30) TextButton({
                        confirmDelete = null
                        val pi = MediaStore.createDeleteRequest(ctx.contentResolver, onPhone.map { android.net.Uri.parse(it.localUri) })
                        deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }) { Text("スマホからだけ") }
                    TextButton({ confirmDelete = null }) { Text("やめる") }
                }
            })
    }
    moveOf?.let { list ->
        AlertDialog(onDismissRequest = { moveOf = null },
            title = { Text("${list.size} 件の置き場所") },
            text = { Text(if (list.isEmpty()) "Synologyにある写真が選ばれていません（スマホにしかない写真は、同期したあとで移せます）。"
                          else "家族で共有：家族みんなが見られる photo フォルダへ。\n自分だけ：自分の Photos フォルダへ。") },
            confirmButton = {
                if (list.isNotEmpty()) TextButton({
                    moveOf = null; selected = emptySet()
                    scope.launch {
                        toast = try { "家族で共有へ ${app.net.api()!!.moveMedia(com.photohub.app.data.api.MoveBody(list.map { it.hash!! }, "shared"))["moved"] ?: 0} 件移しました" }
                                catch (e: Exception) { "移せませんでした（${e.message}）" }
                        com.photohub.app.data.SyncWorker.runNow(ctx)
                    }
                }) { Text("家族で共有へ") }
            },
            dismissButton = {
                Row {
                    if (list.isNotEmpty()) TextButton({
                        moveOf = null; selected = emptySet()
                        scope.launch {
                            toast = try { "自分だけへ ${app.net.api()!!.moveMedia(com.photohub.app.data.api.MoveBody(list.map { it.hash!! }, "mine"))["moved"] ?: 0} 件移しました" }
                                    catch (e: Exception) { "移せませんでした（${e.message}）" }
                            com.photohub.app.data.SyncWorker.runNow(ctx)
                        }
                    }) { Text("自分だけへ") }
                    TextButton({ moveOf = null }) { Text("やめる") }
                }
            })
    }
    albumOf?.let { list ->
        AddToAlbumDialog(list, onDone = { msg -> albumOf = null; selected = emptySet(); toast = msg })
    }
    toast?.let { t ->
        androidx.compose.runtime.LaunchedEffect(t) { android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_LONG).show(); toast = null }
    }
}

private data class Header(val label: String, val key: String, val first: Int)

/** Small segmented pill row (年月日 / 写真動画). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> Pills(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp)) {
        options.forEach { (label, v) ->
            val on = v == selected
            Text(label, modifier = Modifier.clip(RoundedCornerShape(50))
                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                .combinedClickable(onClick = { onPick(v) })
                .padding(horizontal = 14.dp, vertical = 6.dp),
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

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
    val loaded by app.lists.timeline.collectAsState()
    val items = loaded ?: run { Loading(); return }
    val today = LocalDate.now()
    val memories = remember(items) {
        items.filter { val d = localDate(it); d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year }
            .groupBy { today.year - localDate(it).year }.toSortedMap()
    }
    MediaGrid(items, onOpen, zoomBar = true, header = {
      Column {
        MarksLegend()
        if (memories.isNotEmpty()) LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
      }
    })
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)
