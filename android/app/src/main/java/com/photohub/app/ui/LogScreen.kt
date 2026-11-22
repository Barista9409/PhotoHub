package com.photohub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.AppLog
import com.photohub.app.data.api.LogSummary
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * 「動作の記録」: what the family's phones reported to the Synology in the last week —
 * stops (grouped by where they happened), errors, and how long things take (slowest first).
 * The copy button puts it all in one text to send to whoever looks after the app.
 */
@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf<LogSummary?>(null) }
    var msg by remember { mutableStateOf<String?>("読み込み中…") }
    LaunchedEffect(refresh) {
        val api = app.net.api()
        if (api == null) { msg = "Synologyにつながると表示されます"; return@LaunchedEffect }
        try { AppLog.upload(api, com.photohub.app.data.deviceName(app)) } catch (_: Exception) {}   // this phone's latest first
        try { data = api.logSummary(7); msg = null }
        catch (e: retrofit2.HttpException) { msg = if (e.code() == 404) "Synology側が古い版です。新しい版になると表示されます" else "読めませんでした（HTTP ${e.code()}）" }
        catch (e: Exception) { msg = "読めませんでした（${e.message}）" }
    }
    val d = data
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        Text("この1週間に、家族のスマホがSynologyに送った記録です（Synologyの中だけに14日間残ります）。" +
            "止まった・失敗した・時間がかかった処理を、多い順・遅い順に並べています。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = d != null, onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("PhotoHub 動作の記録", asText(d!!)))
                msg = "コピーしました。そのまま貼り付けて送れます"
            }) { Text("まとめてコピー") }
            OutlinedButton({ msg = "読み込み中…"; refresh++ }) { Text("更新") }
        }
        msg?.let { Text(it, Modifier.padding(16.dp, 8.dp)) }
        if (d == null) return@Column

        SectionTitle("スマホごと")
        Group {
            if (d.devices.isEmpty()) Text("まだ記録がありません", Modifier.padding(16.dp))
            d.devices.forEach { v ->
                Line(v.device, "v${v.version ?: "?"}・最後 ${when_(v.last)}・停止 ${v.stops} 回・失敗 ${v.errors} 回")
            }
        }
        SectionTitle("止まった（${d.stops.sumOf { it.count }} 回）")
        Group {
            if (d.stops.isEmpty()) Text("ありません", Modifier.padding(16.dp))
            d.stops.forEach { g -> Line("${g.count} 回・${when_(g.last)}・v${g.version ?: "?"}", g.what) }
        }
        SectionTitle("うまくいかなかった処理")
        Group {
            if (d.errors.isEmpty()) Text("ありません", Modifier.padding(16.dp))
            d.errors.forEach { g -> Line(g.what, "${g.count} 回・最後 ${when_(g.last)}") }
        }
        SectionTitle("かかった時間（遅い順）")
        Group {
            if (d.timing.isEmpty()) Text("まだ記録がありません", Modifier.padding(16.dp))
            d.timing.forEach { t ->
                Line(t.name, "ふつう ${sec(t.median)}・遅いとき ${sec(t.p95)}・最大 ${sec(t.max)}（${t.count} 回）")
            }
        }
    }
}

@Composable
private fun Line(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun sec(ms: Long) = if (ms < 1000) "$ms ms" else "%.1f 秒".format(ms / 1000.0)

private fun when_(ms: Long): String = if (ms <= 0) "?" else
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).let { "%d/%d %d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

private fun asText(d: LogSummary): String = buildString {
    append("PhotoHub 動作の記録（${d.days}日間）\n\n[スマホ]\n")
    d.devices.forEach { append("- ${it.device} v${it.version} 停止${it.stops} 失敗${it.errors} 最後${when_(it.last)}\n") }
    append("\n[止まった]\n")
    d.stops.forEach { append("- ${it.count}回 v${it.version} ${it.what}\n${it.sample.lines().take(12).joinToString("\n") { l -> "    $l" }}\n") }
    append("\n[失敗]\n")
    d.errors.forEach { append("- ${it.count}回 ${it.what}\n") }
    append("\n[時間]\n")
    d.timing.forEach { append("- ${it.name}: 中央${it.median}ms p95 ${it.p95}ms 最大${it.max}ms (${it.count}回)\n") }
}
