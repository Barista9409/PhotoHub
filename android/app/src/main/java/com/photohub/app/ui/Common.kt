package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    Place("nas", androidx.compose.material.icons.Icons.Default.Dns, Color(0xFF2E7D32), "Synology"),
    Place("drive", androidx.compose.material.icons.Icons.Default.Cloud, Color(0xFF00897B), "Googleドライブ"),
    Place("google", androidx.compose.material.icons.Icons.Default.PhotoLibrary, Color(0xFF1565C0), "Googleフォト"),
    Place("amazon", androidx.compose.material.icons.Icons.Default.Inventory2, Color(0xFFEF6C00), "Amazonフォト"),
)
val NOT_BACKED_UP = Place("none", androidx.compose.material.icons.Icons.Default.CloudOff, Color(0xFFE65100), "まだスマホにしかありません")

/** The places shown as marks: this phone, the Synology, Google Drive. */
val SHOWN_PLACES = setOf("phone", "nas", "drive")

fun placesOf(stores: String): List<Place> {
    val set = stores.split(',').filter { it.isNotBlank() }.toSet()
    return PLACES.filter { it.key in set && it.key in SHOWN_PLACES }
}

/** True when the photo exists only on this phone (nothing else holds a copy yet). */
fun onlyOnPhone(stores: String): Boolean = placesOf(stores).map { it.key } == listOf("phone")

/**
 * Store marks on a thumbnail: one small white icon per place on a dark pill
 * (phone, NAS, Drive, Google Photos). Only on this phone = one orange "not backed up" mark.
 */
@Composable
fun StoreBadges(stores: String, modifier: Modifier = Modifier) {
    val places = placesOf(stores)
    if (places.isEmpty()) return
    Row(modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        places.forEach { p ->
            androidx.compose.material3.Icon(p.icon, p.label, tint = Color.White, modifier = Modifier.size(12.dp))
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

/** Short names for the legend strip. */
private fun shortLabel(p: Place) = when (p.key) {
    "phone" -> "スマホ"; "nas" -> "Synology"; "drive" -> "Googleドライブ"; "google" -> "Gフォト"; "none" -> "スマホだけ（まだ送っていない）"; else -> p.label
}

/** The strip at the top of the photo list: what the marks on the thumbnails mean. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MarksLegend(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("写真の左下の印＝その写真がある場所", fontSize = 12.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PLACES.filter { it.key in SHOWN_PLACES }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(3.dp)) {
                        androidx.compose.material3.Icon(p.icon, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(shortLabel(p), fontSize = 12.sp)
                }
            }
        }
    }
}

/** "What do the marks mean?" (info sheet) */
@Composable
fun PlaceLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PLACES.filter { it.key in SHOWN_PLACES }.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) { PlaceChip(p) }
        }
        Text("一覧では、写真の左下に小さなアイコンで出ます。スマホの印だけの写真は、まだこのスマホにしかありません。", fontSize = 12.sp)
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


/** Shown while a list is still being read (instead of "no photos"). */
@Composable
fun Loading(text: String = "読み込み中…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.CircularProgressIndicator()
            Text(text, fontSize = 14.sp)
        }
    }
}


/**
 * "Delete from where?" - one tick box per place (Synology / Google Drive / this phone).
 * A place that holds none of the chosen photos is greyed out.
 */
@Composable
fun DeleteDialog(items: List<com.photohub.app.data.db.TimelineItem>, onDismiss: () -> Unit,
                 onDelete: (nas: Boolean, drive: Boolean, phone: Boolean) -> Unit) {
    val nasN = items.count { it.hash != null }
    val driveN = items.count { it.driveId != null }
    val phoneN = items.count { it.localUri != null }
    var nas by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(nasN > 0) }
    var drive by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(driveN > 0) }
    var phone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(phoneN > 0) }
    @Composable
    fun Line(p: Place, label: String, n: Int, checked: Boolean, set: (Boolean) -> Unit) {
        val on = n > 0
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.Checkbox(checked && on, { set(it) }, enabled = on)
            androidx.compose.material3.Icon(p.icon, null, modifier = Modifier.size(18.dp),
                tint = if (on) p.color else androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            Spacer(Modifier.width(8.dp))
            Text(if (on) (if (items.size > 1) "$label（$n 件）" else label) else "$label（ありません）", fontSize = 15.sp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface.copy(alpha = if (on) 1f else 0.38f))
        }
    }
    val any = (nas && nasN > 0) || (drive && driveN > 0) || (phone && phoneN > 0)
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (items.size > 1) "${items.size} 件を削除" else "削除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("消す場所を選んでください", fontSize = 13.sp)
                Line(PLACES.first { it.key == "nas" }, "Synology（30日間ゴミ箱から戻せます）", nasN, nas) { nas = it }
                Line(PLACES.first { it.key == "drive" }, "Googleドライブ", driveN, drive) { drive = it }
                Line(PLACES.first { it.key == "phone" }, "このスマホ", phoneN, phone) { phone = it }
                if (phone && phoneN > 0 && !(nas && nasN > 0) && nasN < phoneN)
                    Text("Synologyにまだない写真をスマホから消すと、元に戻せません。", fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                if (phone && phoneN > 0 && android.os.Build.VERSION.SDK_INT >= 31) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    if (!android.provider.MediaStore.canManageMedia(ctx))
                        androidx.compose.material3.TextButton(onClick = {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                        }) { Text("次から Android の確認を省く（「メディアの管理」を許可）", fontSize = 12.sp) }
                }
                if (drive && driveN > 0 && !phone && phoneN > 0)
                    Text("スマホに残っている写真は、次の保存でまたドライブに入ります。", fontSize = 12.sp,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = any, onClick = { onDelete(nas && nasN > 0, drive && driveN > 0, phone && phoneN > 0) }) { Text("削除") } },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text("やめる") } })
}
