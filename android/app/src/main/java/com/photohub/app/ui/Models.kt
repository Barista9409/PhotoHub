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
import android.graphics.Bitmap
import android.graphics.Matrix
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import java.io.File

/** Shared, in-memory UI state: the NAS base URL and the list the viewer pages through. */
object UiState {
    private val _base = MutableStateFlow<String?>(null)
    val base: StateFlow<String?> = _base

    var viewerItems: List<TimelineItem> = emptyList()

    /** Photos turned upright (hash -> clockwise degrees), kept current from the database. */
    val rots = MutableStateFlow<Map<String, Int>>(emptyMap())

    suspend fun refreshBase(app: PhotoHubApp, force: Boolean = false) {
        _base.value = if (app.prefs.current().configured) app.net.baseUrl(force) else null
    }
}

/**
 * Image source for an item, cheapest first (spec F6 "写真を開く"):
 * phone copy -> device thumbnail/preview -> NAS (only when reachable).
 */
/** Turns the picture clockwise (photos set upright in PhotoHub; the file itself is untouched). */
class Rotate(private val degrees: Int) : Transformation() {
    override val cacheKey = "rotate-$degrees"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        Bitmap.createBitmap(input, 0, 0, input.width, input.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
}

/** The image to load, turned upright when someone rotated it. */
fun turned(app: PhotoHubApp, item: TimelineItem, data: Any?): Any? =
    if (data == null || item.rot % 360 == 0 || item.kind == "video") data
    else ImageRequest.Builder(app).data(data).transformations(Rotate(item.rot)).build()

/** Rotate photos on the Synology (90 = clockwise); the phone shows it right away. */
suspend fun rotatePhotos(app: PhotoHubApp, hashes: List<String>, degrees: Int): String = try {
    val api = app.net.api() ?: return "Synologyにつながりません"
    val r = api.rotate(com.photohub.app.data.api.RotateBody(hashes, degrees)).rotation
    r.forEach { (h, rot) -> app.db.media().setRot(h, rot) }
    UiState.rots.value = UiState.rots.value + r
    if (r.isEmpty()) "回転できる写真がありません（動画やスマホにしかない写真は回せません）" else "${r.size} 枚を回転しました"
} catch (e: retrofit2.HttpException) {
    if (e.code() == 404) "Synology側が古い版です。新しい版に更新してください" else "回転できませんでした（HTTP ${e.code()}）"
} catch (e: Exception) { "Synologyにつながりません" }

/** The item with its latest turn (lists handed to the viewer are snapshots). */
private fun current(item: TimelineItem): TimelineItem =
    item.hash?.let { UiState.rots.value[it] ?: 0 }?.takeIf { it != item.rot }?.let { item.copy(rot = it) } ?: item

fun thumbModel(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = current(item).let { turned(app, it, rawThumb(app, it, base)) }

fun previewModel(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = current(item).let { turned(app, it, rawPreview(app, it, base)) }

/** Thumbnail of a Synology photo known only by its hash (album covers, suggestions, similar photos). */
fun hashThumb(app: PhotoHubApp, hash: String, base: String?): Any? {
    val data: Any? = if (app.files.hasThumb(hash)) app.files.thumb(hash) else base?.let { "$it/api/thumb/$hash" }
    val rot = UiState.rots.value[hash] ?: 0
    return if (data == null || rot == 0) data else ImageRequest.Builder(app).data(data).transformations(Rotate(rot)).build()
}

private fun rawThumb(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = when {
    item.localUri != null -> Uri.parse(item.localUri)
    item.hash != null && app.files.hasThumb(item.hash) -> app.files.thumb(item.hash)
    item.hash != null && base != null -> "$base/api/thumb/${item.hash}"
    item.driveId != null -> app.drive.mediaUrl(item.driveId)   // away without VPN: recent photos from Drive
    else -> null
}

private fun rawPreview(app: PhotoHubApp, item: TimelineItem, base: String?): Any? = when {
    item.localUri != null -> Uri.parse(item.localUri)
    item.hash != null && app.files.hasPreview(item.hash) -> app.files.preview(item.hash)
    item.hash != null && base != null -> "$base/api/preview/${item.hash}"
    item.driveId != null -> app.drive.mediaUrl(item.driveId)
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
