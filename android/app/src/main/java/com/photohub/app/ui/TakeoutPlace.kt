package com.photohub.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.api.MySettings
import kotlinx.coroutines.launch

/** Where photos from *my* Google Takeout go: my personal space, or the family's shared space. */
@Composable
fun TakeoutPlaceSetting() {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        current = try { app.net.api()?.me()?.takeoutTarget } catch (e: Exception) { null }
        if (current == null) note = "NASに接続すると変更できます"
    }
    Column {
        Text("Googleから取り込んだ写真の置き場所", style = MaterialTheme.typography.titleMedium)
        listOf("personal" to "自分だけ（Synology Photosの個人スペース）",
               "shared" to "家族で共有（Synology Photosの共有スペース）").forEach { (key, label) ->
            Row(Modifier.selectable(selected = current == key, enabled = current != null, onClick = {
                scope.launch {
                    note = try {
                        app.net.api()!!.setMySettings(MySettings(key)); current = key
                        "次の取り込みから反映されます。取り込み済みの写真は今の場所のままです。"
                    } catch (e: Exception) { "変更できませんでした: ${e.message}" }
                }
            }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = current == key, onClick = null, enabled = current != null)
                Text(label)
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
