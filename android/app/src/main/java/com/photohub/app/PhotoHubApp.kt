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
    lateinit var lists: com.photohub.app.data.Lists
    lateinit var net: Net
    lateinit var files: MediaFiles
    lateinit var indexer: LocalIndexer
    lateinit var updater: Updater
    lateinit var drive: com.photohub.app.data.DriveClient
    @Volatile var driveToken: String = ""
    // a failure in background work is written down (CrashLog) instead of stopping the whole app
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.write(this, e, fatal = false) })

    @Volatile var token: String = ""

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.INFO).build()

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        prefs = Prefs(this)
        db = AppDb.build(this)
        files = MediaFiles(this)
        net = Net(this, prefs)
        lists = com.photohub.app.data.Lists(this, db, scope)
        scope.launch(Dispatchers.IO) { files.loadIndex() }
        scope.launch { db.media().rotations().collect { l -> com.photohub.app.ui.UiState.rots.value = l.associate { it.hash to it.rot } } }
        indexer = LocalIndexer(this, db.media())
        updater = Updater(this)
        drive = com.photohub.app.data.DriveClient(this)
        OsmConfig.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osm-tiles")
        }
        scope.launch { prefs.settings.collect { token = it.token } }
        // after an app update rebuilt the on-device index, sync the NAS index again from the start
        scope.launch {
            if (prefs.current().cursor > 0 && db.media().allRemoteHashes().isEmpty()) prefs.resetCursor()
        }
        SyncWorker.schedule(this)
        Updater.schedule(this)
        com.photohub.app.data.BackupWorker.schedule(this)
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
        lists.clearCache()
        coil3.SingletonImageLoader.get(this).let { it.diskCache?.clear(); it.memoryCache?.clear() }
    }

    /** Coil: adds the NAS token to remote image/preview requests. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // many small pictures at once (OkHttp allows only 5 per server by default: the grid waited in line)
        val dispatcher = okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 24 }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(okhttp3.ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(8, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.host == "www.googleapis.com") {
                    // the Drive token expires after an hour, and is empty until a backup has run: get one and retry
                    if (driveToken.isEmpty()) kotlinx.coroutines.runBlocking { drive.token() }
                    val first = chain.proceed(req.newBuilder().header("Authorization", "Bearer $driveToken").build())
                    if (first.code != 401) return@addInterceptor first
                    first.close()
                    val fresh = kotlinx.coroutines.runBlocking { drive.refreshToken() } ?: driveToken
                    return@addInterceptor chain.proceed(req.newBuilder().header("Authorization", "Bearer $fresh").build())
                }
                chain.proceed(when {
                    req.url.encodedPath.startsWith("/api/") -> req.newBuilder().header("Authorization", "Bearer $token").build()
                    else -> req
                })
            }.build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(com.photohub.app.ui.DriveThumbFetcher.Factory(this@PhotoHubApp) { client })
                add(com.photohub.app.ui.DriveThumbFetcher.Key())
                add(VideoFrameDecoder.Factory())
            }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("coil")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(false)
            .build()
    }
}
