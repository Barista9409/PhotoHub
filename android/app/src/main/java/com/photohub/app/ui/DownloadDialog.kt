package com.photohub.app.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photohub.app.data.DownloadWorker
import com.photohub.app.data.db.TimelineItem

private fun lastDest(ctx: Context) = ctx.getSharedPreferences("photohub_ui", Context.MODE_PRIVATE).getString("download_dest", "pictures") ?: "pictures"
private fun saveDest(ctx: Context, d: String) = ctx.getSharedPreferences("photohub_ui", Context.MODE_PRIVATE).edit().putString("download_dest", d).apply()

/** Save photos to this phone: how many, and where (the last choice is remembered). */
@Composable
fun DownloadDialog(items: List<TimelineItem>, name: String, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val need = items.filter { it.hash != null && it.localUri == null }
    var dest by remember { mutableStateOf(lastDest(ctx)) }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            dest = "tree:$uri"
        }
    }
    @Composable
    fun Choice(key: String, label: String, onPick: () -> Unit = { dest = key }) {
        val on = if (key == "tree") dest.startsWith("tree:") else dest == key
        Row(Modifier.fillMaxWidth().clickable(onClick = onPick), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(on, onPick)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    AlertDialog(onDismissRequest = { onDone(null) },
        title = { Text("このスマホに保存") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (need.isEmpty()) "選んだ写真は、すべてこのスマホにあります。"
                     else "${need.size} 件（${humanBytes(need.sumOf { it.bytes })}）を保存します。スマホにすでにある写真は飛ばします。",
                    style = MaterialTheme.typography.bodySmall)
                Text("保存する場所", style = MaterialTheme.typography.labelLarge, modifier = androidx.compose.ui.Modifier.fillMaxWidth())
                Choice("pictures", "写真フォルダ（Pictures/PhotoHub/$name）")
                Choice("dcim", "カメラと同じ場所（DCIM/PhotoHub/$name）")
                Choice("download", "ダウンロード（Download/PhotoHub/$name）")
                Choice("tree", if (dest.startsWith("tree:")) "選んだフォルダ：${DownloadWorker.destLabel(dest, name)}" else "フォルダを選ぶ…") { pickTree.launch(null) }
                Text("アプリを閉じても続き、通知で進み具合が見られます。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = need.isNotEmpty() && dest != "tree", onClick = {
                saveDest(ctx, dest)
                val n = DownloadWorker.start(ctx, items, name, dest)
                onDone("$n 件を「${DownloadWorker.destLabel(dest, name)}」に保存しています")
            }) { Text("保存する") }
        },
        dismissButton = { TextButton({ onDone(null) }) { Text("やめる") } })
}
