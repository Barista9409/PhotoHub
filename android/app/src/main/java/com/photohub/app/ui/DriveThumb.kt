package com.photohub.app.ui

import com.photohub.app.PhotoHubApp
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.FileSystem

/** A Google Drive file's small picture (Drive makes it), instead of downloading the whole original. */
data class DriveThumb(val id: String)

class DriveThumbFetcher(private val app: PhotoHubApp, private val data: DriveThumb, private val http: OkHttpClient) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        // the interceptor of `http` adds the Drive token (and renews it)
        val meta = http.newCall(Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/${data.id}?fields=thumbnailLink").build()).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body!!.string()
        }
        val link = Regex("\"thumbnailLink\"\\s*:\\s*\"([^\"]+)\"").find(meta)?.groupValues?.get(1)
            ?.replace("\\u003d", "=")?.replace(Regex("=s\\d+$"), "=s400") ?: return@withContext null
        val bytes = http.newCall(Request.Builder().url(link).header("Authorization", "Bearer ${app.driveToken}").build())
            .execute().use { r -> if (r.isSuccessful) r.body!!.bytes() else null } ?: return@withContext null
        SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), null, DataSource.NETWORK)
    }

    class Factory(private val app: PhotoHubApp, private val http: () -> OkHttpClient) : Fetcher.Factory<DriveThumb> {
        override fun create(data: DriveThumb, options: Options, imageLoader: ImageLoader): Fetcher = DriveThumbFetcher(app, data, http())
    }

    class Key : Keyer<DriveThumb> {
        override fun key(data: DriveThumb, options: Options) = "drive-thumb:${data.id}"
    }
}
