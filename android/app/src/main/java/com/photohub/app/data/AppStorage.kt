package com.photohub.app.data

import android.content.Context
import com.photohub.app.PhotoHubApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** How much of the phone's storage PhotoHub uses, part by part. */
object AppStorage {
    data class Usage(val thumbs: Long, val previews: Long, val imageCache: Long, val database: Long, val other: Long) {
        val total get() = thumbs + previews + imageCache + database + other
    }

    private fun size(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun measure(app: PhotoHubApp): Usage = withContext(Dispatchers.IO) {
        val thumbs = app.files.thumbBytes()
        val previews = app.files.previewBytes()
        val coil = size(File(app.cacheDir, "coil"))
        val dbDir = app.getDatabasePath("photohub.db").parentFile
        val db = dbDir?.let { size(it) } ?: 0
        val all = size(app.filesDir) + size(app.cacheDir) + db
        Usage(thumbs, previews, coil, db, (all - thumbs - previews - coil - db).coerceAtLeast(0))
    }

    /** Things that are fetched again when needed: the image cache, files made for sharing, downloaded updates. */
    suspend fun clearTemporary(app: PhotoHubApp) = withContext(Dispatchers.IO) {
        coil3.SingletonImageLoader.get(app).let { it.diskCache?.clear(); it.memoryCache?.clear() }
        app.files.clearShare()
        File(app.cacheDir, "updates").deleteRecursively()
        app.cacheDir.listFiles()?.filter { it.name.startsWith("download-") }?.forEach { it.delete() }
    }
}
