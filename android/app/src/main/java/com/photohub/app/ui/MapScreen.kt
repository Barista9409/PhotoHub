package com.photohub.app.ui

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
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
            // rebuild the dots only when the set of points changes (not on every recomposition)
            val sig = points.size.toString() + ":" + (points.firstOrNull()?.itemKey ?: "")
            if (mv.getTag(com.photohub.app.R.id.map_points) == sig) return@AndroidView
            mv.setTag(com.photohub.app.R.id.map_points, sig)
            mv.overlays.removeAll { it is SimpleFastPointOverlay }
            if (points.isNotEmpty()) {
                val pts = points.map { LabelledGeoPoint(it.lat, it.lon, it.itemKey) }
                val theme = SimplePointTheme(pts, false)
                val paint = Paint().apply { style = Paint.Style.FILL; color = AColor.parseColor("#E0D84315") }
                val opts = SimpleFastPointOverlayOptions.getDefaultStyle()
                    .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
                    .setRadius(10f).setIsClickable(true).setCellSize(16).setPointStyle(paint)
                val overlay = SimpleFastPointOverlay(theme, opts)
                overlay.setOnClickListener { _, i ->
                    val key = (pts[i!!] as LabelledGeoPoint).label
                    index[key]?.let { onOpen(timeline, it) }
                }
                mv.overlays.add(overlay)
                if (mv.tag == null) {
                    mv.tag = "fitted"
                    fit(mv, points.map { it.lat }, points.map { it.lon })
                }
            }
            mv.invalidate()
        })
        if (points.isEmpty()) {
            Text("位置情報のある写真がまだありません。\nスマホの写真は、バックアップの準備（写真の確認）が進むと表示されます。",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(16.dp))
        } else {
            Text("${points.size} 枚", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            SmallFloatingActionButton(onClick = { fit(map, points.map { it.lat }, points.map { it.lon }) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Default.ZoomOutMap, "全体を表示") }
        }
    }
}


/** Zoom to show all points; a single place gets a sensible street-level zoom. */
private fun fit(mv: MapView, lats: List<Double>, lons: List<Double>) {
    if (lats.isEmpty()) return
    mv.post {
        if (lats.max() - lats.min() < 0.002 && lons.max() - lons.min() < 0.002) {
            mv.controller.setZoom(15.0)
            mv.controller.setCenter(GeoPoint(lats.average(), lons.average()))
        } else {
            mv.zoomToBoundingBox(BoundingBox(lats.max(), lons.max(), lats.min(), lons.min()).increaseByScale(1.2f), false, 48)
        }
    }
}
