package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.launch

/** Pending action that needs a "you are on mobile data" confirmation (spec F6). */
private data class Confirm(val title: String, val text: String, val ok: String = "OK", val run: () -> Unit)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ViewerScreen(start: Int, onBack: () -> Unit) {
    val items = remember { UiState.viewerItems }
    if (items.isEmpty()) { onBack(); return }
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    val favs by app.prefs.favorites.collectAsState(initial = emptySet())
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var deleteOf by remember { mutableStateOf<TimelineItem?>(null) }
    val phoneDelete = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch { app.indexer.scan() }
        onBack()
    }
    var playing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var barsVisible by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    var dragY by remember { mutableFloatStateOf(0f) }
    // slideshow: the next photo every 4 seconds, screen kept on; a tap stops it
    var slideshow by remember { mutableStateOf(UiState.slideshow.also { UiState.slideshow = false }) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(slideshow) {
        view.keepScreenOn = slideshow
        if (slideshow) barsVisible = false
        while (slideshow) {
            kotlinx.coroutines.delay(4000)
            if (pager.currentPage < items.lastIndex) pager.animateScrollToPage(pager.currentPage + 1)
            else { slideshow = false; barsVisible = true; message = "スライドショーが終わりました" }
        }
    }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }

    fun guarded(item: TimelineItem, what: String, action: () -> Unit) {
        val remote = item.localUri == null
        if (remote && app.net.isMetered()) {
            confirm = Confirm("モバイル回線です", "$what（${humanBytes(item.bytes)}）をSynologyから取得します。よろしいですか？", "取得する", action)
        } else action()
    }

    val alpha = (1f - kotlin.math.abs(dragY) / 1200f).coerceIn(0.3f, 1f)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = alpha))
        .pointerInput(Unit) {
            // swipe down (or up) to close, like Google Photos
            detectVerticalDragGestures(
                onDragEnd = { if (kotlin.math.abs(dragY) > 250f) onBack() else dragY = 0f },
                onDragCancel = { dragY = 0f },
            ) { change, dy -> dragY += dy; change.consume() }
        }) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize().graphicsLayer(translationY = dragY,
            scaleX = 1f - kotlin.math.abs(dragY) / 4000f, scaleY = 1f - kotlin.math.abs(dragY) / 4000f),
            key = { "$it:${items[it].itemKey}" }) { page ->
            val rots by UiState.rots.collectAsState()
            val item = items[page].let { it0 -> it0.hash?.let { h -> it0.copy(rot = rots[h] ?: 0) } ?: it0 }
            val auto = item.kind == "video" && page == pager.currentPage && (item.localUri != null || !app.net.isMetered())
            if (item.kind == "video" && (playing == item.itemKey || auto)) {
                VideoPlayer(app, item, base)
            } else {
                ZoomableImage(previewModel(app, item, base), item.name, onTap = {
                    if (slideshow) { slideshow = false; barsVisible = true; message = "スライドショーを止めました" } else barsVisible = !barsVisible
                })
                if (item.kind == "video") {
                    Button(onClick = { guarded(item, "動画の再生") { playing = item.itemKey } },
                        modifier = Modifier.align(Alignment.Center)) {
                        Icon(Icons.Default.PlayArrow, null); Text("再生")
                    }
                }
            }
        }
        val cur = items[pager.currentPage]
        if (barsVisible && dragY == 0f) Column(Modifier.fillMaxWidth().statusBarsPadding().background(Color(0x66000000)).padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(formatDate(cur), color = Color.White)
                    Row(verticalAlignment = Alignment.CenterVertically) { StoreBadges(cur.stores); Gap(6); Text(cur.name, color = Color(0xCCFFFFFF), maxLines = 1) }
                }
                cur.hash?.let { h ->
                    IconButton(onClick = { scope.launch { app.prefs.toggleFavorite(h) } }) {
                        Icon(if (h in favs) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "お気に入り", tint = Color.White)
                    }
                }
                IconButton(onClick = {
                    guarded(cur, "原本の取得") {
                        scope.launch {
                            message = "準備中…"
                            val uri = fetchOriginal(ctx, app, cur, base)
                            message = null
                            if (uri != null) shareUri(ctx, uri, cur.mime) else message = "Synologyに接続できません"
                        }
                    }
                }) { Icon(Icons.Default.Share, "共有", tint = Color.White) }
                if (cur.hash != null && cur.kind == "photo") IconButton(onClick = {
                    scope.launch { val m = rotatePhotos(app, listOf(cur.hash), 90); if (!m.endsWith("回転しました")) message = m }
                }) { Icon(Icons.Default.Rotate90DegreesCw, "右に回転", tint = Color.White) }
                IconButton(onClick = { deleteOf = cur }) { Icon(Icons.Default.Delete, "削除", tint = Color.White) }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Default.Info, "情報", tint = Color.White) }
            }
            message?.let { Text(it, color = Color.White, modifier = Modifier.padding(8.dp)) }
        }
    }
    if (showInfo) {
        val cur = items[pager.currentPage]
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            Column(Modifier.padding(20.dp, 0.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatDate(cur, "yyyy年M月d日(E) H:mm"), style = MaterialTheme.typography.titleMedium)
                Text(cur.name)
                Text(listOfNotNull(cur.w?.let { w -> cur.h?.let { "${w}×${it}" } }, humanBytes(cur.bytes),
                    cur.duration?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) }).joinToString("・"))
                cur.place?.let { Text("撮った場所：$it", style = MaterialTheme.typography.bodyMedium) }
                Text("保存されている場所", style = MaterialTheme.typography.labelLarge)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ps = placesOf(cur.stores)
                    ps.forEach { PlaceChip(it) }
                }
                // where exactly: the folder on each of them
                val whereLines by androidx.compose.runtime.produceState(emptyList<Pair<String, String>>(), cur.itemKey) {
                    val out = mutableListOf<Pair<String, String>>()
                    cur.hash?.let { h -> app.db.media().remote(h) }?.let { r ->
                        // the folder as File Station shows it
                        val root = when (r.space) { "shared" -> listOf("photo"); "mine" -> listOf("home", "Photos"); else -> listOf("homes", "（家族の個人フォルダ）", "Photos") }
                        out += "Synology（File Station）" to (root + r.folder.split('/').filter { it.isNotBlank() }).joinToString(" / ")
                    }
                    cur.localUri?.let { u -> out += "このスマホ" to (listOf("内部ストレージ") + (app.db.media().localRelPath(u) ?: "")
                        .split('/').filter { it.isNotBlank() }).joinToString(" / ") }
                    if ("drive" in cur.stores.split(',')) out += "Googleドライブ" to "マイドライブ / PhotoHub"
                    value = out
                }
                whereLines.forEach { (where, path) ->
                    Column {
                        Text(where, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(path, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (cur.kind == "video" && cur.hash != null) {
                    var probe by remember { mutableStateOf<String?>(null) }
                    androidx.compose.material3.TextButton({
                        probe = "調べています…"
                        scope.launch {
                            probe = try {
                                val j = app.net.api()!!.probe(cur.hash)
                                fun str(e: kotlinx.serialization.json.JsonElement?) = (e as? kotlinx.serialization.json.JsonPrimitive)?.content
                                val streams = (j["streams"] as? kotlinx.serialization.json.JsonArray)?.joinToString("\n") { st ->
                                    val o = st as kotlinx.serialization.json.JsonObject
                                    listOfNotNull(str(o["codec_type"]), str(o["codec_name"]), str(o["profile"]), str(o["pix_fmt"]),
                                        str(o["color_transfer"]), str(o["width"])?.let { "${it}×${str(o["height"])}" }).joinToString(" ")
                                } ?: ""
                                val fmt = j["format"] as? kotlinx.serialization.json.JsonObject
                                listOf("ファイル：${if (str(j["exists"]) == "true") "あり" else "Synologyにありません"}",
                                    "大きさ：${str(j["bytes"])} バイト（一覧では ${str(j["indexed_bytes"])}）",
                                    "長さ：${str(fmt?.get("duration"))} 秒・${str(fmt?.get("format_name"))}",
                                    "目次が先頭：${if (str(j["faststart"]) == "true") "はい" else "いいえ（最後）"}",
                                    "すぐ再生できるコピー：${if (str(j["ready_copy"]) == "true") "あり" else "まだ"}",
                                    streams, str(j["errors"])?.takeIf { it.isNotBlank() }?.let { "エラー：$it" } ?: "")
                                    .filter { it.isNotBlank() }.joinToString("\n")
                            } catch (e: retrofit2.HttpException) { if (e.code() == 404) "Synology側が古い版です" else "調べられませんでした（HTTP ${e.code()}）" }
                              catch (e: Exception) { "調べられませんでした（${e.message}）" }
                        }
                    }) { Text("この動画を調べる（再生できないとき）") }
                    probe?.let { text ->
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
                        androidx.compose.material3.TextButton({
                            ctx.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(android.content.ClipData.newPlainText("PhotoHub", "${cur.name}\n$text"))
                        }) { Text("結果をコピー") }
                    }
                }
                var legend by remember { mutableStateOf(false) }
                androidx.compose.material3.TextButton({ legend = !legend }) { Text(if (legend) "印の見かたを閉じる" else "一覧の印の見かた") }
                if (legend) PlaceLegend()
                if (cur.lat != null && cur.lon != null) {
                    Text("撮影場所：%.5f, %.5f".format(cur.lat, cur.lon))
                    TextButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("geo:${cur.lat},${cur.lon}?q=${cur.lat},${cur.lon}")))
                    }) { Text("地図アプリで開く") }
                }
            }
        }
    }
    deleteOf?.let { item ->
        DeleteDialog(listOf(item), onDismiss = { deleteOf = null }) { nas, drive, phone ->
            deleteOf = null
            scope.launch {
                val o = com.photohub.app.data.Deleter.everywhere(app, listOf(item), nas, drive, phone)
                if (o.phoneUris.isNotEmpty() && android.os.Build.VERSION.SDK_INT >= 30) {
                    val pi = android.provider.MediaStore.createDeleteRequest(ctx.contentResolver, o.phoneUris)
                    phoneDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
                }
                message = o.error
                com.photohub.app.data.SyncWorker.runNow(ctx)
                if (o.error == null && o.phoneUris.isEmpty()) onBack()
            }
        }
    }
    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(c.title) }, text = { Text(c.text) },
            confirmButton = { TextButton({ confirm = null; c.run() }) { Text(c.ok) } },
            dismissButton = { TextButton({ confirm = null }) { Text("やめる") } })
    }
}

@Composable
fun ZoomableImage(model: Any?, desc: String, onTap: () -> Unit = {}) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    AsyncImage(
        model = model, contentDescription = desc, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->  // double-tap: zoom in around the finger, again to reset
                        if (scale > 1f) { scale = 1f; ox = 0f; oy = 0f }
                        else { scale = 2.5f; ox = (size.width / 2f - p.x) * 1.5f; oy = (size.height / 2f - p.y) * 1.5f }
                    },
                )
            }
            .pointerInput(Unit) {
                // pinch (2 fingers) or pan while zoomed; single-finger swipes go to the pager
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy),
    )
}

private val PLAY_VIA_SYNOLOGY = setOf("mts", "m2ts", "avi", "wmv", "mpg", "mpeg", "vob", "flv", "asf", "mod", "tod", "3g2", "divx")

@Composable
fun VideoPlayer(app: PhotoHubApp, item: TimelineItem, base: String?) {
    val ctx = LocalContext.current
    var error by remember(item.itemKey) { mutableStateOf<String?>(null) }
    // where to play from, best first: the phone's own file, the Synology, Google Drive
    // formats phones cannot play (AVCHD .MTS, AVI, WMV, MPEG-2 ...): the Synology makes an MP4 first
    val needsMp4 = item.name.substringAfterLast('.', "").lowercase() in PLAY_VIA_SYNOLOGY
    val plain = remember(item.itemKey, base) {
        val mp4 = if (item.hash != null && base != null) "playable" else null
        if (needsMp4) listOfNotNull(mp4, item.localUri, originalUrl(base, item))
        else listOfNotNull(item.localUri, originalUrl(base, item), item.driveId?.let { app.drive.mediaUrl(it) }, mp4)
    }
    // the Synology makes phone-friendly copies ahead of time: when one is ready, start with it
    var ready by remember(item.itemKey, base) { mutableStateOf<Boolean?>(if (item.localUri != null || item.hash == null || base == null) false else null) }
    androidx.compose.runtime.LaunchedEffect(item.itemKey, base) {
        if (ready != null) return@LaunchedEffect
        ready = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                app.net.client(app.token).newBuilder().callTimeout(4, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(okhttp3.Request.Builder().url("$base/api/playable/${item.hash}").head().build()).execute().use { it.code == 200 }
            } catch (e: Exception) { false }
        }
    }
    val sources = remember(plain, ready) {
        if (ready == true) listOf("$base/api/playable/${item.hash}") + plain.filter { it != "playable" } else plain
    }
    var at by remember(item.itemKey) { mutableStateOf(0) }
    var preparing by remember(item.itemKey) { mutableStateOf(false) }   // the Synology is making its copy
    // bytes that arrived for the current source (shown while it loads)
    val loaded = remember(item.itemKey) { java.util.concurrent.atomic.AtomicLong(0) }
    val counter = remember(item.itemKey) {
        object : androidx.media3.datasource.TransferListener {
            override fun onTransferInitializing(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean, bytes: Int) { loaded.addAndGet(bytes.toLong()) }
            override fun onTransferEnd(source: androidx.media3.datasource.DataSource, dataSpec: androidx.media3.datasource.DataSpec, isNetwork: Boolean) {}
        }
    }
    val player = remember(item.itemKey) { ExoPlayer.Builder(ctx).build() }
    androidx.compose.runtime.LaunchedEffect(item.itemKey, at, sources) {
        if (ready == null) return@LaunchedEffect   // still asking whether a ready copy exists
        var uri = sources.getOrNull(at) ?: run { error = "再生できる場所がありません（Synologyにつながっていないかもしれません）"; return@LaunchedEffect }
        error = null
        if (uri == "playable") {
            preparing = true
            error = null   // the progress overlay says what is going on
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    app.net.client(app.token).newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.MINUTES).build()
                        .newCall(okhttp3.Request.Builder().url("$base/api/playable/${item.hash}")
                            .post(ByteArray(0).toRequestBody(null)).build()).execute().use { it.isSuccessful }
                } catch (e: Exception) { false }
            }
            preparing = false
            if (!ok) { if (at + 1 < sources.size) at++ else error = "変換できませんでした"; return@LaunchedEffect }
            error = null
            uri = "$base/api/playable/${item.hash}"
        }
        val http = if (uri.contains("googleapis.com")) {
            app.drive.token()
            OkHttpDataSource.Factory(okhttp3.OkHttpClient()).setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${app.driveToken}"))
        } else OkHttpDataSource.Factory(app.net.client(app.token))
        http.setTransferListener(counter)
        loaded.set(0)
        val src = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http)).createMediaSource(MediaItem.fromUri(uri))
        player.setMediaSource(src)
        player.prepare()
        player.playWhenReady = true
    }
    // a video that does not start (e.g. a big phone video read over a slow connection, or one whose
    // index sits at the end of the file): after 20 seconds, try the next place - last of all the
    // Synology's ready-to-stream copy - and write down what happened (エラーの記録)
    var started by remember(item.itemKey) { mutableStateOf(false) }
    var buffering by remember(item.itemKey) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(item.itemKey, at, started) {
        if (started) return@LaunchedEffect
        kotlinx.coroutines.delay(20_000)
        while (preparing) kotlinx.coroutines.delay(1000)   // making the copy can take minutes: wait for it
        if (sources.getOrNull(at) == "playable") kotlinx.coroutines.delay(20_000)   // then give it its 20 seconds
        if (!started) {
            // what the player knows: little buffered = the network; plenty buffered = the decoder (format)
            val v = player.videoFormat; val au = player.audioFormat
            com.photohub.app.data.AppLog.error("動画が始まらない",
                msg = "${item.name} ${humanBytes(item.bytes)} 場所${at + 1}/${sources.size}: ${sources.getOrNull(at)?.substringBefore("?")}" +
                    " 状態=${player.playbackState} 読込中=${player.isLoading} 読込済=${player.bufferedPosition}ms" +
                    " 映像=${v?.sampleMimeType}/${v?.codecs}/${v?.width}x${v?.height}/${v?.colorInfo}" +
                    " 音声=${au?.sampleMimeType}/${au?.codecs}/${au?.sampleRate}Hz/${au?.channelCount}ch")
            if (at + 1 < sources.size) at++
            else error = "再生を始められませんでした。通信が遅いか、この動画の形式に対応していないかもしれません"
        }
    }
    DisposableEffect(item.itemKey) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                com.photohub.app.data.AppLog.error("動画の再生", e, "${item.name}: ${e.errorCodeName}")
                if (at + 1 < sources.size) at++   // try the next place
                else error = "再生できませんでした（${e.errorCodeName}）"
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                if (state == androidx.media3.common.Player.STATE_READY) started = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
            modifier = Modifier.fillMaxSize())
        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(16.dp))
        }
        // progress while it loads: how much arrived, how fast, and how close it is to starting
        if (error == null && !started && (buffering || preparing)) {
            val where = sources.getOrNull(at).orEmpty().let { u ->
                when { u.startsWith("content:") -> "このスマホ"; u.contains("googleapis") -> "Googleドライブ"
                       u.contains("/api/playable") || u == "playable" -> "Synology（再生用）"; else -> "Synology" } }
            var line by remember { mutableStateOf("") }
            var toStart by remember { mutableStateOf(0f) }
            androidx.compose.runtime.LaunchedEffect(at, preparing) {
                val t0 = System.currentTimeMillis()
                var last = loaded.get(); var lastT = t0
                while (true) {
                    kotlinx.coroutines.delay(500)
                    val now = System.currentTimeMillis()
                    val secs = (now - t0) / 1000
                    line = if (preparing) "Synologyで、スマホですぐ再生できる形に整えています… %d:%02d（長い動画は数分かかります）".format(secs / 60, secs % 60)
                    else {
                        val b = loaded.get()
                        val speed = (b - last) * 1000.0 / maxOf(1, now - lastT)
                        last = b; lastT = now
                        toStart = (player.bufferedPosition / 2500f).coerceIn(0f, 1f)   // the player starts after about 2.5 s
                        "読み込み中（$where から）${humanBytes(b)} / ${humanBytes(item.bytes)}・${humanBytes(speed.toLong())}/秒・%d秒".format(secs)
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 120.dp)
                .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(14.dp)) {
                Text(line.ifEmpty { "読み込み中…（$where から）" }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                if (preparing) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                else androidx.compose.material3.LinearProgressIndicator(progress = { toStart }, modifier = Modifier.fillMaxWidth())
                if (!preparing) Text("再生が始まるまで ${(toStart * 100).toInt()}%", color = Color(0xCCFFFFFF),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
