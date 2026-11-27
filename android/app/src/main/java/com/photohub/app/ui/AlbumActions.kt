package com.photohub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.AlbumWithCount
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The ︙ of the album selection bar: the same things as the ︙ inside one album — ファミリー／マイフォトへ
 * (share / copy / move), ダウンロード, 名前を変える (one album) — for every chosen album at once.
 * [report] shows the result; [clear] ends the selection.
 */
@Composable
fun AlbumPickMenu(chosen: List<AlbumWithCount>, report: (String?) -> Unit, clear: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var space by remember { mutableStateOf<List<AlbumWithCount>?>(null) }
    var rename by remember { mutableStateOf<AlbumWithCount?>(null) }
    var download by remember { mutableStateOf<Pair<List<TimelineItem>, String>?>(null) }
    Box {
        IconButton({ open = true }) { Icon(Icons.Default.MoreVert, "ほかの操作") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("ファミリー／マイフォトへ…") }, leadingIcon = { Icon(Icons.Default.FamilyRestroom, null) },
                onClick = { open = false; space = chosen })
            DropdownMenuItem(text = { Text("ダウンロード") }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = {
                open = false
                val list = chosen
                scope.launch {
                    // every photo of the chosen albums, once each
                    val items = list.flatMap { app.db.media().album(it.id).first() }.distinctBy { it.itemKey }
                    download = items to (list.singleOrNull()?.name ?: "アルバム ${list.size} 件")
                }
            })
            val pins by com.photohub.app.data.Pins.ids.collectAsState()
            val allPinned = chosen.isNotEmpty() && chosen.all { it.id in pins }
            DropdownMenuItem(text = { Text(if (allPinned) "ピン止めを外す" else "ピン止め") },
                leadingIcon = { Icon(Icons.Default.PushPin, null) }, onClick = {
                    open = false
                    com.photohub.app.data.Pins.set(app, chosen.map { it.id }, !allPinned)
                    clear()
                    report(if (allPinned) "ピン止めを外しました" else "アルバムの一覧のいちばん上に出します")
                })
            if (chosen.size == 1) DropdownMenuItem(text = { Text("名前を変える") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { open = false; rename = chosen.first() })
        }
    }
    space?.let { list ->
        SpaceDialog("${if (list.size == 1) "「${list[0].name}」" else "アルバム ${list.size} 件"}をどこへ",
            startToFamily = list.any { !it.shared }, chooseDest = true, album = true, onDismiss = { space = null }) { fam, how ->
            space = null; clear()
            scope.launch {
                // albums already on that side are left as they are
                val todo = list.filter { it.shared != fam }
                report(when {
                    todo.isEmpty() -> "選んだアルバムは、もう${if (fam) "ファミリー" else "マイフォト"}にあります"
                    todo.size == 1 -> runAlbumSpace(app, todo[0].id, fam, how)
                    else -> {
                        val bad = todo.map { runAlbumSpace(app, it.id, fam, how) }.count { it.contains("できませんでした") || it.contains("古い版") }
                        "アルバム ${todo.size - bad} 件を${if (fam) "ファミリー" else "マイフォト"}へ" +
                            when (how) { SpaceHow.SHARE -> "共有"; SpaceHow.COPY -> "コピー"; SpaceHow.MOVE -> "移動" } + "しました" +
                            (if (bad > 0) "（$bad 件はできませんでした）" else "")
                    }
                })
            }
        }
    }
    rename?.let { a ->
        var v by remember(a.id) { mutableStateOf(a.name) }
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("アルバムの名前") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = v.isNotBlank(), onClick = {
                rename = null; clear()
                scope.launch {
                    report(try { app.net.api()!!.renameAlbum(a.id, com.photohub.app.data.api.AlbumNameBody(v.trim())); "名前を「${v.trim()}」にしました" }
                           catch (e: Exception) { "名前を変えられませんでした（${e.message}）" })
                    com.photohub.app.data.SyncWorker.runNow(app, light = true)
                }
            }) { Text("変える") } },
            dismissButton = { TextButton({ rename = null }) { Text("やめる") } })
    }
    download?.let { (items, name) ->
        DownloadDialog(items, name) { msg -> download = null; clear(); report(msg) }
    }
}
