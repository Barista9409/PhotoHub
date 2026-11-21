package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photohub.app.data.db.TimelineItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun PhotoHubTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF8FB8E8), secondary = Color(0xFFF2B33D))
        else lightColorScheme(primary = Color(0xFF2F64B0), secondary = Color(0xFFB57A00)),
        content = content,
    )
}

/** Where a photo is kept: icon, colour and the words used in the legend and the info sheet. */
data class Place(val key: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color, val label: String)

val PLACES = listOf(
    Place("phone", androidx.compose.material.icons.Icons.Default.PhoneAndroid, Color(0xFF546E7A), "このスマホ"),
    Place("nas", androidx.compose.material.icons.Icons.Default.Dns, Color(0xFF2E7D32), "NAS"),
    Place("drive", androidx.compose.material.icons.Icons.Default.Cloud, Color(0xFF00897B), "Googleドライブ"),
    Place("google", androidx.compose.material.icons.Icons.Default.PhotoLibrary, Color(0xFF1565C0), "Googleフォト"),
    Place("amazon", androidx.compose.material.icons.Icons.Default.Inventory2, Color(0xFFEF6C00), "Amazonフォト"),
)
val NOT_BACKED_UP = Place("none", androidx.compose.material.icons.Icons.Default.CloudOff, Color(0xFFE65100), "まだバックアップされていません")

fun placesOf(stores: String): List<Place> {
    val set = stores.split(',').filter { it.isNotBlank() }.toSet()
    return PLACES.filter { it.key in set }
}

/** True when the photo exists only on this phone (nothing else holds a copy yet). */
fun onlyOnPhone(stores: String): Boolean = placesOf(stores).map { it.key } == listOf("phone")

/**
 * Store marks on a thumbnail: small white icons on a dark pill, one per place.
 * A photo that is only on this phone gets a single orange "not backed up" mark instead.
 */
@Composable
fun StoreBadges(stores: String, modifier: Modifier = Modifier) {
    val places = if (onlyOnPhone(stores)) listOf(NOT_BACKED_UP) else placesOf(stores).filter { it.key != "phone" }
    if (places.isEmpty()) return
    Row(modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        places.forEach { p ->
            androidx.compose.material3.Icon(p.icon, p.label, tint = if (p == NOT_BACKED_UP) Color(0xFFFFB74D) else Color.White,
                modifier = Modifier.size(12.dp))
        }
    }
}

/** Labelled version for the info sheet and the legend. */
@Composable
fun PlaceChip(p: Place) {
    Row(Modifier.background(p.color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.Icon(p.icon, null, tint = p.color, modifier = Modifier.size(16.dp))
        Text(p.label, color = p.color, fontSize = 13.sp)
    }
}

/** "What do the marks mean?" */
@Composable
fun PlaceLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PLACES.filter { it.key != "amazon" && it.key != "phone" }.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) { PlaceChip(p); Gap(8); Text("に保存されています", fontSize = 13.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { PlaceChip(NOT_BACKED_UP) }
        Text("スマホにもある写真は、一覧では印を付けず、情報画面に「このスマホ」と出ます。", fontSize = 12.sp)
    }
}

fun zoneFor(tz: Int?): ZoneId = tz?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()

fun formatDate(item: TimelineItem, pattern: String = "yyyy年M月d日 H:mm"): String =
    DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(item.takenAt).atZone(zoneFor(item.tz)))

fun monthKey(item: TimelineItem): String =
    DateTimeFormatter.ofPattern("yyyy年M月").format(Instant.ofEpochMilli(item.takenAt).atZone(zoneFor(item.tz)))

fun humanBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun Gap(w: Int = 8) = Spacer(Modifier.width(w.dp))
