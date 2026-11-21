package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** Where an item lives: 端末 / NAS / G / A (spec F6 store badges). */
@Composable
fun StoreBadges(stores: String, modifier: Modifier = Modifier) {
    val set = stores.split(',').filter { it.isNotBlank() }.toSet()
    Row(modifier) {
        listOf("phone" to "端", "nas" to "N", "drive" to "D", "google" to "G", "amazon" to "A").forEach { (k, label) ->
            if (k in set) {
                Box(
                    Modifier.padding(end = 2.dp).background(badgeColor(k), RoundedCornerShape(3.dp))
                        .padding(horizontal = 3.dp, vertical = 0.dp),
                ) { Text(label, color = Color.White, fontSize = 9.sp) }
            }
        }
    }
}

private fun badgeColor(k: String) = when (k) {
    "phone" -> Color(0xCC546E7A)
    "nas" -> Color(0xCC2E7D32)
    "google" -> Color(0xCC1565C0)
    "drive" -> Color(0xCC00897B)
    else -> Color(0xCCEF6C00)
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
