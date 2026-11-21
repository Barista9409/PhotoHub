package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.SyncWorker
import com.photohub.app.data.api.Status
import com.photohub.app.data.db.KindCount

private fun List<KindCount>.photos() = firstOrNull { it.kind == "photo" }?.n ?: 0
private fun List<KindCount>.videos() = firstOrNull { it.kind == "video" }?.n ?: 0
private fun List<KindCount>.bytes() = sumOf { it.bytes }
private fun num(n: Int) = "%,d".format(n)

/** Where everything is: phone / NAS / Google Drive / Google Photos, photos and videos, count and size. */
@Composable
fun LibraryScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val dao = app.db.media()
    val phone by dao.phoneCounts().collectAsState(initial = emptyList())
    val nas by dao.nasCounts().collectAsState(initial = emptyList())
    val drive by dao.driveCounts().collectAsState(initial = emptyList())
    val google by dao.googleCounts().collectAsState(initial = emptyList())
    val phoneOnly by dao.phoneOnlyCounts().collectAsState(initial = emptyList())
    val unchecked by dao.uncheckedCount().collectAsState(initial = 0)
    var status by remember { mutableStateOf<Status?>(null) }
    LaunchedEffect(Unit) { status = try { app.net.api()?.status() } catch (e: Exception) { null } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("写真と動画が、どこに何枚あるか。1枚が複数の場所にあることもあるので、合計は一致しません。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val warn = phoneOnly.photos() + phoneOnly.videos()
        if (warn > 0 || unchecked > 0) {
            Card(NOT_BACKED_UP, "このスマホにしかない", phoneOnly,
                note = buildString {
                    append("NASにもドライブにもまだない写真です。スマホをなくすと消えてしまいます。")
                    if (unchecked > 0) append("\n（うち ${num(unchecked)} 枚はまだ確認していません。バックアップを実行すると確認します）")
                }, highlight = true)
        }
        Card(PLACES.first { it.key == "phone" }, "このスマホ", phone)
        Card(PLACES.first { it.key == "nas" }, "NAS（あなたが見られる分）", nas,
            note = status?.let { "NASの集計：写真 ${num(it.photos)} 枚・動画 ${num(it.videos)} 本（家族の共有スペースを含む）" }
                ?: "NASにつながると、NAS側の集計も表示します")
        Card(PLACES.first { it.key == "drive" }, "Googleドライブ", drive,
            note = if (!app.prefs.settings.collectAsState(initial = null).value.let { it?.backupDrive ?: false })
                "ドライブへのバックアップはオフです" else null)
        if (google.isNotEmpty()) Card(PLACES.first { it.key == "google" }, "Googleフォト（Takeoutで確認できた分）", google)

        OutlinedButton({ SyncWorker.runNow(ctx) }, Modifier.fillMaxWidth()) { Text("NASの一覧を読み込み直す") }
    }
}

@Composable
private fun Card(p: Place, title: String, counts: List<KindCount>, note: String? = null, highlight: Boolean = false) {
    Column(Modifier.fillMaxWidth()
        .background(if (highlight) p.color.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, null, tint = p.color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(humanBytes(counts.bytes()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("写真", "${num(counts.photos())} 枚", Modifier.weight(1f))
            Stat("動画", "${num(counts.videos())} 本", Modifier.weight(1f))
            Stat("合計", num(counts.photos() + counts.videos()), Modifier.weight(1f))
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** One-line summary for the account screen. */
@Composable
fun librarySummary(): String {
    val dao = (LocalContext.current.applicationContext as PhotoHubApp).db.media()
    val phone by dao.phoneCounts().collectAsState(initial = emptyList())
    val nas by dao.nasCounts().collectAsState(initial = emptyList())
    val drive by dao.driveCounts().collectAsState(initial = emptyList())
    val only by dao.phoneOnlyCounts().collectAsState(initial = emptyList())
    val o = only.photos() + only.videos()
    return "スマホ ${num(phone.photos() + phone.videos())}・NAS ${num(nas.photos() + nas.videos())}・ドライブ ${num(drive.photos() + drive.videos())}" +
        if (o > 0) "\nまだバックアップされていない ${num(o)}" else ""
}
