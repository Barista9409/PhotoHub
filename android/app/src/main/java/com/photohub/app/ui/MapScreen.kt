package com.photohub.app.ui

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme

/**
 * Map of every geotagged item. The points come from the on-device DB, so they show even in
 * airplane mode; map tiles are OpenStreetMap's and appear offline only where already cached.
 */
@Composable
fun MapScreen(onOpen: (List<TimelineItem>, Int) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val points by app.db.media().geo().collectAsState(initial = emptyList())
    val timeline by app.db.media().timeline().collectAsState(initial = emptyList())
    val index = remember(timeline) { timeline.withIndex().associate { it.value.itemKey to it.index } }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(5.0)
            controller.setCenter(GeoPoint(36.2, 138.2))
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize(), update = { mv ->
            mv.overlays.removeAll { it is SimpleFastPointOverlay }
            if (points.isNotEmpty()) {
                val pts = points.map { LabelledGeoPoint(it.lat, it.lon, it.itemKey) }
                val theme = SimplePointTheme(pts, false)
                val paint = Paint().apply { style = Paint.Style.FILL; color = AColor.parseColor("#E0D84315") }
                val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setRadius(9f).setIsClickable(true).setCellSize(14).setPointStyle(paint)
                val overlay = SimpleFastPointOverlay(theme, opts)
                overlay.setOnClickListener { _, i ->
                    val key = (pts[i!!] as LabelledGeoPoint).label
                    val pos = index[key]
                    if (pos != null) onOpen(timeline, pos)
                }
                mv.overlays.add(overlay)
                if (mv.tag == null) {
                    mv.tag = "fitted"
                    val lats = points.map { it.lat }; val lons = points.map { it.lon }
                    mv.post { mv.zoomToBoundingBox(BoundingBox(lats.max(), lons.max(), lats.min(), lons.min()).increaseByScale(1.2f), false) }
                }
            }
            mv.invalidate()
        })
        Text("${points.size} 件の位置情報", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}
