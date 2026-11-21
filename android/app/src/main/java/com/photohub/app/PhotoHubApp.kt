package com.photohub.app

import android.app.Application
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.photohub.app.data.LocalIndexWorker
import com.photohub.app.data.LocalIndexer
import com.photohub.app.data.MediaFiles
import com.photohub.app.data.Net
import com.photohub.app.data.Prefs
import com.photohub.app.data.SyncWorker
import com.photohub.app.data.Updater
import com.photohub.app.data.db.AppDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration as OsmConfig
import java.util.concurrent.TimeUnit

class PhotoHubApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    lateinit var prefs: Prefs
    lateinit var db: AppDb
    lateinit var net: Net
    lateinit var files: MediaFiles
    lateinit var indexer: LocalIndexer
    lateinit var updater: Updater
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var token: String = ""

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = AppDb.build(this)
        net = Net(this, prefs)
        files = MediaFiles(this)
        indexer = LocalIndexer(this, db.media())
        updater = Updater(this)
        OsmConfig.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osm-tiles")
        }
        scope.launch { prefs.settings.collect { token = it.token } }
        SyncWorker.schedule(this)
        Updater.schedule(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "photohub-local-index", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<LocalIndexWorker>(1, TimeUnit.HOURS).build(),
        )
    }

    /** A different family member's token: forget the previous person's NAS index and copies. */
    suspend fun switchUser() {
        prefs.resetCursor()
        db.media().clearRemote()
        db.media().clearAlbums()
        files.clearAll()
        coil3.SingletonImageLoader.get(this).let { it.diskCache?.clear(); it.memoryCache?.clear() }
    }

    /** Coil: adds the NAS token to remote image/preview requests. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (req.url.encodedPath.startsWith("/api/")) req.newBuilder().header("Authorization", "Bearer $token").build() else req)
            }.build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(VideoFrameDecoder.Factory())
            }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("coil")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(false)
            .build()
    }
}
