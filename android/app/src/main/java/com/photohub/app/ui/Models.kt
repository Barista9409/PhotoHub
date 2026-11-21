package com.photohub.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Shared, in-memory UI state: the NAS base URL and the list the viewer pages through. */
object UiState {
    private val _base = MutableStateFlow<String?>(null)
    val base: StateFlow<String?> = _base

    var viewerItems: List<TimelineItem> = emptyList()

    suspend fun refreshBase(app: PhotoHubApp, force: Boolean = false) {
        _base.value = if (app.prefs.current().configured) app.net.baseUrl(force) else null
    }
}

/**
 * Image source for an item, cheapest first (spec F6 "写真を開く"):
 * phone copy -> device thumbnail/preview -> NAS (only when reachable).
 */
fun thumbModel(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = when {
    item.localUri != null -> Uri.parse(item.localUri)
    item.hash != null && app.files.hasThumb(item.hash) -> app.files.thumb(item.hash)
    item.hash != null && base != null -> "$base/api/thumb/${item.hash}"
    else -> null
}

fun previewModel(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = when {
    item.localUri != null -> Uri.parse(item.localUri)
    item.hash != null && app.files.hasPreview(item.hash) -> app.files.preview(item.hash)
    item.hash != null && base != null -> "$base/api/preview/${item.hash}"
    item.hash != null && app.files.hasThumb(item.hash) -> app.files.thumb(item.hash)
    else -> null
}

fun originalUrl(base: String?, item: TimelineItem): String? = item.hash?.let { h -> base?.let { "$it/api/original/$h" } }

/** Original for sharing/saving: the phone file if we have it, else download from the NAS. */
suspend fun fetchOriginal(ctx: Context, app: PhotoHubApp, item: TimelineItem, base: String?): Uri? = withContext(Dispatchers.IO) {
    item.localUri?.let { return@withContext Uri.parse(it) }
    val url = originalUrl(base, item) ?: return@withContext null
    val dir = app.files.shareDir.apply { mkdirs() }
    val out = File(dir, item.name.ifBlank { "photo" })
    app.net.client(app.prefs.current().token).newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) return@withContext null
        out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
    }
    FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
}

fun shareUri(ctx: Context, uri: Uri, mime: String?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime ?: "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
