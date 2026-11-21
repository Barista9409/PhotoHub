package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
private data class Confirm(val title: String, val text: String, val run: () -> Unit)

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
    var playing by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun guarded(item: TimelineItem, what: String, action: () -> Unit) {
        val remote = item.localUri == null
        if (remote && app.net.isMetered()) {
            confirm = Confirm("モバイル回線です", "$what（${humanBytes(item.bytes)}）をNASから取得します。よろしいですか？", action)
        } else action()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].itemKey }) { page ->
            val item = items[page]
            if (item.kind == "video" && playing == item.itemKey) {
                VideoPlayer(app, item, base)
            } else {
                ZoomableImage(previewModel(app, item, base), item.name)
                if (item.kind == "video") {
                    Button(onClick = { guarded(item, "動画の再生") { playing = item.itemKey } },
                        modifier = Modifier.align(Alignment.Center)) {
                        Icon(Icons.Default.PlayArrow, null); Text("再生")
                    }
                }
            }
        }
        val cur = items[pager.currentPage]
        Column(Modifier.fillMaxWidth().statusBarsPadding().background(Color(0x66000000)).padding(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(formatDate(cur), color = Color.White)
                    Row { StoreBadges(cur.stores); Gap(4); Text(cur.name, color = Color(0xCCFFFFFF), maxLines = 1) }
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
                            if (uri != null) shareUri(ctx, uri, cur.mime) else message = "NASに接続できません"
                        }
                    }
                }) { Icon(Icons.Default.Share, "共有", tint = Color.White) }
            }
            message?.let { Text(it, color = Color.White, modifier = Modifier.padding(8.dp)) }
        }
    }
    confirm?.let { c ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(c.title) }, text = { Text(c.text) },
            confirmButton = { TextButton({ confirm = null; c.run() }) { Text("取得する") } },
            dismissButton = { TextButton({ confirm = null }) { Text("やめる") } })
    }
}

@Composable
fun ZoomableImage(model: Any?, desc: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    AsyncImage(
        model = model, contentDescription = desc, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
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

@Composable
fun VideoPlayer(app: PhotoHubApp, item: TimelineItem, base: String?) {
    val ctx = LocalContext.current
    val player = remember(item.itemKey) {
        val http = OkHttpDataSource.Factory(app.net.client(app.token))
        val ds = DefaultDataSource.Factory(ctx, http)
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build().apply {
            val uri = item.localUri ?: originalUrl(base, item)
            if (uri != null) {
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
                playWhenReady = true
            }
        }
    }
    DisposableEffect(item.itemKey) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = Modifier.fillMaxSize())
}
