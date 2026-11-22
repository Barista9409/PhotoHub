package com.photohub.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Photo map like Google Photos: a heatmap when zoomed out, photo thumbnails (grouped, with a count)
 * when zoomed in. Tapping a thumbnail opens the photos taken there.
 */
@Composable
fun MapScreen(onOpen: (List<TimelineItem>, Int) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val loaded by app.lists.timeline.collectAsState()
    val timeline = loaded ?: emptyList()
    val base by UiState.base.collectAsState()
    val located = remember(timeline) { timeline.filter { it.lat != null && it.lon != null && !(it.lat == 0.0 && it.lon == 0.0) } }
    val overlay = remember { PhotoMapOverlay(app) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 3.0
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(36.2, 138.2))
            overlays.add(overlay)
        }
    }
    overlay.onOpen = onOpen
    overlay.base = base
    LaunchedEffect(located) {
        val first = overlay.items.isEmpty()
        overlay.setItems(located)
        if (first && located.isNotEmpty()) fit(map, located)
        map.invalidate()
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
        if (loaded == null) {
            Text("読み込み中…", modifier = Modifier.align(Alignment.Center)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(16.dp))
        } else if (located.isEmpty()) {
            Text("位置情報のある写真がまだありません。\nスマホの写真は、同期の「スマホの写真を確認中」が終わると表示されます。",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(16.dp))
        } else {
            Text("${located.size} 枚", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp))
            SmallFloatingActionButton(onClick = { fit(map, located) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.ZoomOutMap, "全体を表示") }
        }
    }
}

/** Zoom to show all points; a single place gets a street-level zoom. */
private fun fit(mv: MapView, items: List<TimelineItem>) {
    if (items.isEmpty()) return
    val lats = items.map { it.lat!! }
    val lons = items.map { it.lon!! }
    mv.post {
        if (lats.max() - lats.min() < 0.002 && lons.max() - lons.min() < 0.002) {
            mv.controller.setZoom(16.0)
            mv.controller.setCenter(GeoPoint(lats.average(), lons.average()))
        } else {
            mv.zoomToBoundingBox(BoundingBox(lats.max(), lons.max(), lats.min(), lons.min()).increaseByScale(1.25f), false, 64)
        }
    }
}

/** Heatmap below THUMB_ZOOM, grouped thumbnails from it on. */
class PhotoMapOverlay(private val app: PhotoHubApp) : Overlay() {
    var items: List<TimelineItem> = emptyList(); private set
    var onOpen: (List<TimelineItem>, Int) -> Unit = { _, _ -> }
    var base: String? = null

    private val density = app.resources.displayMetrics.density
    private val thumbs = LruCache<String, Bitmap>(240)
    private val loading = HashSet<String>()
    private var clusters: List<Cluster> = emptyList()
    private var mapView: MapView? = null

    private class Cluster(val rect: RectF, val items: List<TimelineItem>)

    fun setItems(list: List<TimelineItem>) { items = list.sortedByDescending { it.takenAt } }

    // ---- heatmap ----
    private val heatScale = 4                      // draw the heat at 1/4 resolution, then scale up smoothly
    private val stampRadius = (22 * density / heatScale).toInt().coerceAtLeast(4)
    private val stamp: Bitmap = Bitmap.createBitmap(stampRadius * 2, stampRadius * 2, Bitmap.Config.ARGB_8888).also { b ->
        Canvas(b).drawCircle(stampRadius.toFloat(), stampRadius.toFloat(), stampRadius.toFloat(), Paint().apply {
            shader = RadialGradient(stampRadius.toFloat(), stampRadius.toFloat(), stampRadius.toFloat(),
                0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        })
    }
    private var heatAlpha: Bitmap? = null
    private var heatColor: Bitmap? = null
    private var pixels = IntArray(0)
    private val palette = IntArray(256) { a -> heatColorFor(a) }
    private val stampPaint = Paint()
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    // ---- thumbnails ----
    private val cell = 64 * density
    private val thumbSize = 54 * density
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0BEC5.toInt() }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A73E8.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 11 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun draw(c: Canvas, mv: MapView, shadowPass: Boolean) {
        if (shadowPass || items.isEmpty()) return
        mapView = mv
        val proj = mv.projection
        val w = c.width; val h = c.height
        val pt = Point()
        val screen = ArrayList<Pair<TimelineItem, Point>>(items.size)
        val margin = (thumbSize).toInt()
        for (it in items) {
            proj.toPixels(GeoPoint(it.lat!!, it.lon!!), pt)
            if (pt.x < -margin || pt.y < -margin || pt.x > w + margin || pt.y > h + margin) continue
            screen += it to Point(pt)
        }
        val zoom = mv.zoomLevelDouble
        if (zoom < THUMB_ZOOM) {
            clusters = emptyList()
            drawHeat(c, screen, w, h)
        } else {
            drawThumbs(c, screen)
        }
    }

    private fun drawHeat(c: Canvas, pts: List<Pair<TimelineItem, Point>>, w: Int, h: Int) {
        val sw = max(1, w / heatScale); val sh = max(1, h / heatScale)
        val alpha = heatAlpha?.takeIf { it.width == sw && it.height == sh }
            ?: Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).also { heatAlpha = it; heatColor = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888); pixels = IntArray(sw * sh) }
        alpha.eraseColor(0)
        val ac = Canvas(alpha)
        // group nearby points first, so 10,000 photos at home are one strong spot, not 10,000 draws
        val counts = HashMap<Long, Int>()
        val bucket = max(1, stampRadius / 3)
        for ((_, p) in pts) {
            val bx = (p.x / heatScale) / bucket; val by = (p.y / heatScale) / bucket
            val k = (bx.toLong() shl 32) or (by.toLong() and 0xffffffffL)
            counts[k] = (counts[k] ?: 0) + 1
        }
        for ((k, n) in counts) {
            val bx = (k shr 32).toInt(); val by = k.toInt()
            // more photos -> stronger, but a single photo is still visible
            stampPaint.alpha = (70 + 185 * min(1.0, sqrt(n / 12.0))).toInt().coerceIn(0, 255)
            ac.drawBitmap(stamp, (bx * bucket + bucket / 2 - stampRadius).toFloat(), (by * bucket + bucket / 2 - stampRadius).toFloat(), stampPaint)
        }
        alpha.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        for (i in pixels.indices) pixels[i] = palette[pixels[i] ushr 24]
        val color = heatColor!!
        color.setPixels(pixels, 0, sw, 0, 0, sw, sh)
        c.drawBitmap(color, Rect(0, 0, sw, sh), Rect(0, 0, w, h), scalePaint)
    }

    private fun drawThumbs(c: Canvas, pts: List<Pair<TimelineItem, Point>>) {
        // greedy grid grouping in screen space; the newest photo in a group is its cover
        val groups = LinkedHashMap<Long, MutableList<Pair<TimelineItem, Point>>>()
        for (e in pts) {
            val gx = (e.second.x / cell).toInt(); val gy = (e.second.y / cell).toInt()
            groups.getOrPut((gx.toLong() shl 32) or (gy.toLong() and 0xffffffffL)) { ArrayList() } += e
        }
        val out = ArrayList<Cluster>(groups.size)
        val r = 10 * density
        for (g in groups.values) {
            val cx = g.map { it.second.x }.average().toFloat()
            val cy = g.map { it.second.y }.average().toFloat()
            val rect = RectF(cx - thumbSize / 2, cy - thumbSize / 2, cx + thumbSize / 2, cy + thumbSize / 2)
            val list = g.map { it.first }
            out += Cluster(rect, list)
            val b = 2.5f * density
            c.drawRoundRect(RectF(rect.left - b, rect.top - b + 2 * density, rect.right + b, rect.bottom + b + 2 * density), r + b, r + b, shadow)
            c.drawRoundRect(RectF(rect.left - b, rect.top - b, rect.right + b, rect.bottom + b), r + b, r + b, border)
            val bmp = thumbFor(list.first())
            if (bmp != null) {
                val save = c.save()
                val path = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
                c.clipPath(path)
                // centre-crop
                val s = max(rect.width() / bmp.width, rect.height() / bmp.height)
                val dw = bmp.width * s; val dh = bmp.height * s
                c.drawBitmap(bmp, null, RectF(rect.centerX() - dw / 2, rect.centerY() - dh / 2, rect.centerX() + dw / 2, rect.centerY() + dh / 2), bitmapPaint)
                c.restoreToCount(save)
            } else {
                c.drawRoundRect(rect, r, r, placeholder)
            }
            if (list.size > 1) {
                val label = if (list.size > 999) "999+" else list.size.toString()
                val bw = max(20 * density, badgeText.measureText(label) + 12 * density)
                val bh = 20 * density
                val br = RectF(rect.right - bw + 6 * density, rect.top - 6 * density, rect.right + 6 * density, rect.top - 6 * density + bh)
                c.drawRoundRect(br, bh / 2, bh / 2, badgeBg)
                c.drawText(label, br.centerX(), br.centerY() + badgeText.textSize / 3, badgeText)
            }
        }
        clusters = out
    }

    private fun thumbFor(item: TimelineItem): Bitmap? {
        thumbs.get(item.itemKey)?.let { return it }
        if (!loading.add(item.itemKey)) return null
        val model = thumbModel(app, item, base) ?: return null
        val req = ImageRequest.Builder(app).data(model).size(200).allowHardware(false)
            .target(onSuccess = { img ->
                thumbs.put(item.itemKey, img.toBitmap()); loading.remove(item.itemKey); mapView?.postInvalidate()
            }, onError = { loading.remove(item.itemKey) })
            .build()
        app.imageLoader.enqueue(req)
        return null
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mv: MapView): Boolean {
        if (mv.zoomLevelDouble < THUMB_ZOOM) {
            // tap on the heat: zoom in there
            val near = items.any {
                val p = mv.projection.toPixels(GeoPoint(it.lat!!, it.lon!!), null)
                val dx = p.x - e.x; val dy = p.y - e.y
                dx * dx + dy * dy < (40 * density) * (40 * density)
            }
            if (!near) return false
            val target = mv.projection.fromPixels(e.x.toInt(), e.y.toInt())
            mv.controller.animateTo(target, min(THUMB_ZOOM + 1.0, mv.zoomLevelDouble + 3.0), 400L)
            return true
        }
        val hit = clusters.lastOrNull { it.rect.contains(e.x, e.y) } ?: return false
        onOpen(hit.items, 0)
        return true
    }

    companion object {
        const val THUMB_ZOOM = 11.5

        /** transparent -> blue -> green -> yellow -> red, like a weather map */
        private fun heatColorFor(a: Int): Int {
            if (a < 8) return 0
            val t = a / 255f
            val stops = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
            val cols = intArrayOf(0x3F51B5, 0x00BCD4, 0x8BC34A, 0xFFC107, 0xF44336)
            var i = 0
            while (i < stops.size - 2 && t > stops[i + 1]) i++
            val f = ((t - stops[i]) / (stops[i + 1] - stops[i])).coerceIn(0f, 1f)
            fun lerp(a: Int, b: Int) = (a + (b - a) * f).toInt()
            val c0 = cols[i]; val c1 = cols[i + 1]
            val rr = lerp(c0 shr 16 and 255, c1 shr 16 and 255)
            val gg = lerp(c0 shr 8 and 255, c1 shr 8 and 255)
            val bb = lerp(c0 and 255, c1 and 255)
            val alpha = (min(1f, t * 1.6f) * 200).toInt()
            return (alpha shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
    }
}
