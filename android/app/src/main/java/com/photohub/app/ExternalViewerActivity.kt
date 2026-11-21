package com.photohub.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.IntentCompat
import com.photohub.app.data.db.TimelineItem
import com.photohub.app.ui.PhotoHubTheme
import com.photohub.app.ui.VideoPlayer
import com.photohub.app.ui.ZoomableImage
import com.photohub.app.ui.shareUri

/** Opens an image/video handed over by another app (ACTION_VIEW / ACTION_SEND). */
class ExternalViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> intent.data
        } ?: run { finish(); return }
        val mime = intent.type ?: contentResolver.getType(uri)
        val isVideo = mime?.startsWith("video") == true
        val app = application as PhotoHubApp
        setContent {
            PhotoHubTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (isVideo) {
                        VideoPlayer(app, TimelineItem("ext", null, uri.toString(), "video", 0, null, "", "phone", null, null, 0, null, null, null, mime, null), null)
                    } else {
                        ZoomableImage(uri, "")
                    }
                    Row(Modifier.fillMaxWidth().statusBarsPadding()) {
                        IconButton({ finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る", tint = Color.White) }
                        Box(Modifier.weight(1f))
                        IconButton({ shareUri(this@ExternalViewerActivity, uri, mime) }) { Icon(Icons.Default.Share, "共有", tint = Color.White) }
                    }
                }
            }
        }
    }
}
