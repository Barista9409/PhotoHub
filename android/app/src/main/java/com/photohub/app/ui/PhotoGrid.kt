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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Download
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

/** Rows of the grid: a date header before each new year / month / day, then item indexes. */
private fun buildRows(items: List<TimelineItem>, group: String): List<Any> {
    val out = ArrayList<Any>(items.size + items.size / 8)
    var last: Any? = null
    val today = LocalDate.now()
    val seen = HashSet<String>(items.size * 2)
    items.forEachIndexed { i, it ->
        // the grid stops the app if a key appears twice: never let a repeated item through
        if (!seen.add(it.itemKey)) return@forEachIndexed
        val d = localDate(it)
        val key: Any = when (group) { "year" -> d.year; "month" -> d.withDayOfMonth(1); else -> d }
        if (key != last) {
            out += when (group) {
                // the index keeps keys apart when the same day shows up twice (photos from other time zones)
                "year" -> Header("${d.year}年", "y$key@$i", i)
                "month" -> Header("${d.year}年${d.monthValue}月", "m$key@$i", i)
                else -> Header(dayLabel(d, today), "d$key@$i", i)
            }
            last = key
        }
        out += i
    }
    return out
}

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
@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MediaGrid(allItems: List<TimelineItem>, onOpen: (List<TimelineItem>, Int) -> Unit, header: (@Composable () -> Unit)? = null,
              zoomBar: Boolean = false, albumId: Long? = null, legend: Boolean = true) {
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
    var downloadOf by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var dateOf by remember { mutableStateOf<List<TimelineItem>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    if (allItems.isEmpty() && header == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("写真がありません。写真へのアクセスを許可するか、右上のアイコン →「このスマホ」→「今すぐ一覧を更新」を押してください。",
                modifier = Modifier.padding(24.dp))
        }
        return
    }
    // date headers are worked out away from the screen thread (tens of thousands of photos).
    // The grid draws from the list the rows were made for, so an older list stays on screen
    // (consistent) until the new one is ready.
    var built by remember { mutableStateOf<Pair<List<TimelineItem>, List<Any>>?>(null) }
    androidx.compose.runtime.LaunchedEffect(items, level.group) {
        val group = level.group
        built = items to kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.photohub.app.data.AppLog.timed("日付の見出し作り") { buildRows(items, group) }
        }
    }
    val shown = built?.first ?: emptyList()
    val rows = built?.second ?: emptyList()
    val grid = rememberLazyGridState()
    val headerOffset = 1   // the legend of the marks (and the screen's own header)
    androidx.compose.runtime.LaunchedEffect(Unit) { UiState.scrollTop.collect { grid.animateScrollToItem(0) } }
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
            item(span = { GridItemSpan(maxLineSpan) }) { Column { if (legend) MarksLegend(); header?.invoke() } }
            items(rows, key = { r -> if (r is Header) "h:${r.key}" else shown[r as Int].itemKey },
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
                    val it = shown[idx]
                    val isSel = it.itemKey in selected
                    Box(Modifier.aspectRatio(1f).background(Color(0x22888888))
                        .combinedClickable(
                            onClick = {
                                if (selecting) selected = if (isSel) selected - it.itemKey else selected + it.itemKey
                                else if (level.group != "day") setLevel(DAY_LEVEL, idx)   // like Google Photos: zoom into that day
                                else onOpen(shown, idx)
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
        if (rows.size > 60) FastScroller(grid, rows, shown, Modifier.align(Alignment.CenterEnd))

        // selection bar
        androidx.compose.animation.AnimatedVisibility(visible = selecting, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                var more by remember { mutableStateOf(false) }
                fun chosen() = items.filter { it.itemKey in selected }
                fun onSyno() = chosen().filter { it.hash != null }
                Row(Modifier.padding(horizontal = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, "選択をやめる") }
                    Text("${selected.size} 件", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    val all = items.size == selected.size
                    androidx.compose.material3.TextButton({ selected = if (all) emptySet() else items.map { it.itemKey }.toSet() }) {
                        Text(if (all) "すべて解除" else "すべて選択", maxLines = 1)
                    }
                    IconButton({
                        val list = chosen()
                        scope.launch {
                            busy = "準備中…"
                            val uris = list.mapNotNull { fetchOriginal(ctx, app, it, base) }
                            busy = null
                            if (uris.isNotEmpty()) {
                                val send = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = if (list.all { it.kind == "photo" }) "image/*" else "*/*"
                                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0])
                                    else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ctx.startActivity(Intent.createChooser(send, "共有"))
                            }
                        }
                    }) { Icon(Icons.Default.Share, "共有") }
                    IconButton({ confirmDelete = chosen() }) { Icon(Icons.Default.Delete, "削除", tint = MaterialTheme.colorScheme.error) }
                    Box {
                        IconButton({ more = true }) { Icon(Icons.Default.MoreVert, "ほかの操作") }
                        androidx.compose.material3.DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            @Composable fun entry(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) =
                                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) },
                                    onClick = { more = false; action() })
                            entry(Icons.Default.Delete, "削除") { confirmDelete = chosen() }
                            entry(Icons.Default.Download, "このスマホに保存") { downloadOf = chosen() }
                            entry(Icons.Default.PhotoAlbum, "アルバムに追加") { albumOf = onSyno() }
                            if (albumId != null) entry(Icons.Default.RemoveCircleOutline, "このアルバムから外す") {
                                val hs = onSyno().map { it.hash!! }
                                selected = emptySet()
                                scope.launch {
                                    toast = try { app.net.api()!!.removeFromAlbum(albumId, com.photohub.app.data.api.HashesBody(hs)); "アルバムから外しました" }
                                            catch (e: Exception) { "外せませんでした（${e.message}）" }
                                    com.photohub.app.data.SyncWorker.runNow(ctx)
                                }
                            }
                            entry(Icons.Default.Favorite, "お気に入りにする") {
                                val list = onSyno()
                                scope.launch { list.forEach { app.prefs.toggleFavorite(it.hash!!) } }
                                selected = emptySet()
                            }
                            entry(Icons.Default.Rotate90DegreesCw, "右に回転") {
                                val list = onSyno().filter { it.kind == "photo" }.map { it.hash!! }
                                selected = emptySet()
                                scope.launch { toast = if (list.isEmpty()) "Synologyにある写真を選んでください" else rotatePhotos(app, list, 90) }
                            }
                            entry(Icons.Default.Event, "撮影日を変更") { dateOf = onSyno() }
                            entry(Icons.Default.FamilyRestroom, "自分だけ／家族で共有へ移す") { moveOf = onSyno() }
                        }
                    }
                }
            }
        }
        busy?.let { Text(it, Modifier.align(Alignment.Center).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(16.dp), color = Color.White) }
    }
    }

    confirmDelete?.let { list ->
        DeleteDialog(list, onDismiss = { confirmDelete = null }) { nas, drive, phone ->
            confirmDelete = null
            selected = emptySet()
            scope.launch {
                busy = "削除しています…"
                val o = com.photohub.app.data.Deleter.everywhere(app, list, nas, drive, phone)
                busy = null
                toast = o.error ?: listOfNotNull(if (nas) "Synologyのゴミ箱に ${o.trashed} 件" else null,
                    if (o.fromDrive > 0) "ドライブから ${o.fromDrive} 件" else null).joinToString("・").ifBlank { null }
                if (o.phoneUris.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                    val pi = MediaStore.createDeleteRequest(ctx.contentResolver, o.phoneUris)
                    deleter.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                }
                com.photohub.app.data.SyncWorker.runNow(ctx)
            }
        }
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
    dateOf?.let { list ->
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = list.minOfOrNull { it.takenAt }?.takeIf { it > 0 })
        androidx.compose.material3.DatePickerDialog(onDismissRequest = { dateOf = null },
            confirmButton = { TextButton(enabled = list.isNotEmpty() && state.selectedDateMillis != null, onClick = {
                val day = java.time.Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                dateOf = null; selected = emptySet()
                scope.launch {
                    toast = try { "${app.net.api()!!.setDate(com.photohub.app.data.api.SetDateBody(list.map { it.hash!! }, day))["changed"] ?: 0} 枚の日付を $day にしました" }
                            catch (e: Exception) { "変更できませんでした（${e.message}）" }
                    com.photohub.app.data.SyncWorker.runNow(ctx)
                }
            }) { Text(if (list.isEmpty()) "Synologyにある写真を選んでください" else "${list.size} 枚をこの日付にする") } },
            dismissButton = { TextButton({ dateOf = null }) { Text("やめる") } }) {
            androidx.compose.material3.DatePicker(state, title = { Text("撮影日を選ぶ", modifier = Modifier.padding(16.dp)) })
        }
    }
    downloadOf?.let { list ->
        DownloadDialog(list, "保存した写真") { msg -> downloadOf = null; if (msg != null) { selected = emptySet(); toast = msg } }
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
    // from tapping the app icon to seeing photos (once per start)
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!UiState.startLogged) {
            UiState.startLogged = true
            val ms = android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()
            // a process started earlier for background work is not "opening the app"
            if (ms < 60_000) com.photohub.app.data.AppLog.time("起動から写真が出るまで", ms)
        }
    }
    val today = LocalDate.now()
    val memories = remember(items) {
        items.filter { val d = localDate(it); d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year }
            .groupBy { today.year - localDate(it).year }.toSortedMap()
    }
    MediaGrid(items, onOpen, zoomBar = true, legend = false, header = {
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
