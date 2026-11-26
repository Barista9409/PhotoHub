package com.photohub.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.CopyToBody
import com.photohub.app.data.api.FavoriteBody
import com.photohub.app.data.api.MoveBody

/** How a photo or an album goes between マイフォト and ファミリー. */
enum class SpaceHow { SHARE, COPY, MOVE }

private data class HowOption(val how: SpaceHow, val label: String, val note: String)

private fun options(toFamily: Boolean, album: Boolean) = if (toFamily) listOf(
    HowOption(SpaceHow.SHARE, "共有（おすすめ）", "ファイルはそのまま。家族にも見えるようになります"),
    HowOption(SpaceHow.COPY, "コピー", "家族の共有フォルダにもう1つ置きます（Synology Photos の共有スペースにも出ます）"),
    HowOption(SpaceHow.MOVE, "移動", "家族の共有フォルダへ移します（マイフォトからはなくなります）"),
) else listOf(
    HowOption(SpaceHow.COPY, "コピー", "自分の Photos にもう1つ置きます（家族の" + (if (album) "アルバム" else "写真") + "も残ります）"),
    HowOption(SpaceHow.MOVE, "移動", "自分の Photos へ移します（家族からは見えなくなります）"),
)

/**
 * マイフォト ⇄ ファミリー: where to (when [chooseDest]) and how — share / copy / move.
 * [startToFamily] is the destination it opens with.
 */
@Composable
fun SpaceDialog(title: String, startToFamily: Boolean, chooseDest: Boolean, album: Boolean = false,
                onDismiss: () -> Unit, onRun: (toFamily: Boolean, how: SpaceHow) -> Unit) {
    var toFamily by remember(startToFamily) { mutableStateOf(startToFamily) }
    var how by remember(toFamily) { mutableStateOf(options(toFamily, album).first().how) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chooseDest) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(toFamily, { toFamily = true }, label = { Text("ファミリーへ") })
                    FilterChip(!toFamily, { toFamily = false }, label = { Text("マイフォトへ") })
                }
                options(toFamily, album).forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { how = o.how }.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                        RadioButton(how == o.how, { how = o.how })
                        Column(Modifier.padding(top = 10.dp)) {
                            Text(o.label, style = MaterialTheme.typography.bodyLarge)
                            Text(o.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onRun(toFamily, how) }) { Text(when (how) { SpaceHow.SHARE -> "共有する"; SpaceHow.COPY -> "コピーする"; SpaceHow.MOVE -> "移動する" }) } },
        dismissButton = { TextButton(onDismiss) { Text("やめる") } })
}

private fun failed(e: Exception, what: String) = when (e) {
    is retrofit2.HttpException -> if (e.code() == 404) "Synology側が古い版です" else "${what}できませんでした（HTTP ${e.code()}）"
    else -> "${what}できませんでした（${e.message}）"
}

private fun verb(how: SpaceHow) = when (how) { SpaceHow.SHARE -> "共有"; SpaceHow.COPY -> "コピー"; SpaceHow.MOVE -> "移動" }

/** Photos: runs the choice on the Synology and returns the message to show. */
suspend fun runSpace(app: PhotoHubApp, hashes: List<String>, toFamily: Boolean, how: SpaceHow): String {
    if (hashes.isEmpty()) return "Synologyにある写真を選んでください"
    val api = app.net.api() ?: return "Synologyにつながりません"
    val to = if (toFamily) "shared" else "mine"
    return try {
        val n = when (how) {
            SpaceHow.SHARE -> api.familyShare(FavoriteBody(hashes, true))["changed"] ?: 0
            SpaceHow.COPY -> api.copyMedia(MoveBody(hashes, to))["copied"] ?: 0
            SpaceHow.MOVE -> (api.moveMedia(MoveBody(hashes, to))["moved"] ?: 0) +
                // a photo that was only shared (not moved) goes back to being just mine
                (if (!toFamily) api.familyShare(FavoriteBody(hashes, false))["changed"] ?: 0 else 0)
        }
        val place = if (toFamily) "ファミリー" else "マイフォト"
        if (n == 0) "${verb(how)}する写真はありませんでした（もう${place}にある写真です）"
        else "$n 枚を${place}へ${verb(how)}しました"
    } catch (e: Exception) { failed(e, verb(how)) }
    finally { com.photohub.app.data.SyncWorker.runNow(app, light = true) }
}

/** An album: the same, for the album and its photos. */
suspend fun runAlbumSpace(app: PhotoHubApp, albumId: Long, toFamily: Boolean, how: SpaceHow): String {
    val api = app.net.api() ?: return "Synologyにつながりません"
    val to = if (toFamily) "shared" else "mine"
    val place = if (toFamily) "ファミリー" else "マイフォト"
    return try {
        when (how) {
            SpaceHow.SHARE -> "アルバムを${place}に共有しました（写真 ${api.familyAlbum(albumId)["shared"] ?: 0} 枚。ファイルはそのまま）"
            SpaceHow.COPY -> "アルバムを${place}にコピーしました（写真 ${api.copyAlbum(albumId, CopyToBody(to))["copied"] ?: 0} 枚）"
            SpaceHow.MOVE -> "アルバムを${place}へ移動しました（写真 ${api.moveAlbum(albumId, CopyToBody(to))["moved"] ?: 0} 枚）"
        }
    } catch (e: Exception) { failed(e, verb(how)) }
    finally { com.photohub.app.data.SyncWorker.runNow(app, light = true) }
}
