package com.photohub.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.photohub.app.data.db.TimelineItem
import com.photohub.app.ui.AlbumScreen
import com.photohub.app.ui.AlbumsScreen
import com.photohub.app.ui.MapScreen
import com.photohub.app.ui.PhotoHubTheme
import com.photohub.app.ui.SearchScreen
import com.photohub.app.ui.Dest
import com.photohub.app.ui.SetupScreen
import com.photohub.app.ui.TimelineScreen
import com.photohub.app.ui.UiState
import com.photohub.app.ui.ViewerScreen
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_OPEN_UPDATE = "open_update" }

    /** a photohub://setup link that opened the app (QR code read by another app, or tapped) */
    private val setupLink = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.takeIf { it.scheme == "photohub" }?.let { setupLink.value = it.toString() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PhotoHubApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UiState.refreshBase(app, force = true)
                app.indexer.scan()
                app.updater.check()
                if (app.prefs.current().backupDrive) app.drive.token()  // for viewing Drive copies
            }
        }
        val openUpdate = intent?.getBooleanExtra(EXTRA_OPEN_UPDATE, false) == true
        intent?.data?.takeIf { it.scheme == "photohub" }?.let { setupLink.value = it.toString() }
        setContent { PhotoHubTheme { Root(app, openUpdate, setupLink.value) } }
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("timeline", "フォト", Icons.Default.PhotoLibrary),
    Tab("albums", "アルバム", Icons.Default.PhotoAlbum),
    Tab("shared", "共有", Icons.Default.FamilyRestroom),
    Tab("map", "地図", Icons.Default.Map),
    Tab("search", "検索", Icons.Default.Search),
    Tab("account", "設定", Icons.Default.Settings),   // bottom right: backup, tidy-up, migration, app, account
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(app: PhotoHubApp, openUpdate: Boolean = false, setupLink: String? = null) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "timeline"
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.scope.launch { app.indexer.scan() }
    }
    LaunchedEffect(Unit) {
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        perms.launch(if (Build.VERSION.SDK_INT >= 33) wanted + Manifest.permission.POST_NOTIFICATIONS else wanted)
        if (!app.prefs.current().configured) nav.navigate("setup")
        else {
            // fetch what is new on the NAS (photos Synology Photos backed up, Takeout, other phones)
            com.photohub.app.data.SyncWorker.runNow(app, light = true)
            // and whether a new version of the app is out (only a small check; the banner offers it)
            app.scope.launch { try { app.updater.check() } catch (_: Exception) {} }
            if (openUpdate) nav.navigate(Dest.APP.route)
        }
    }
    LaunchedEffect(setupLink) { if (setupLink != null) nav.navigate("setup") }
    val open: (List<TimelineItem>, Int) -> Unit = { list, i -> UiState.viewerItems = list; nav.navigate("viewer/$i") }
    val fullScreen = route.startsWith("viewer") || route == "setup"
    val upd by app.updater.state.collectAsState()
    val updateWaiting = upd is com.photohub.app.data.Updater.State.Available || upd is com.photohub.app.data.Updater.State.Ready
    Scaffold(
        topBar = {
            if (!fullScreen) TopAppBar(
                title = { Text(titleFor(route, entry?.arguments?.getString("name"))) },
                navigationIcon = {
                    if (tabs.none { it.route == route }) IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                },

            )
        },
        bottomBar = {
            if (!fullScreen) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = {
                        if (route == t.route) UiState.scrollTop.tryEmit(Unit)
                        else nav.navigate(t.route) { popUpTo("timeline") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = {
                        // a dot on 設定 when a new version of the app is waiting
                        if (t.route == "account" && updateWaiting) androidx.compose.material3.BadgedBox(badge = { androidx.compose.material3.Badge() }) { Icon(t.icon, t.label) }
                        else Icon(t.icon, t.label)
                    }, label = { Text(t.label, maxLines = 1) })
                }
            }
        },
    ) { pad ->
        Column(if (fullScreen) Modifier else Modifier.padding(pad)) {
            if (!fullScreen) com.photohub.app.ui.DownloadBanner()
            if (!fullScreen) com.photohub.app.ui.WhatsNewOnce()
            if (tabs.any { it.route == route }) com.photohub.app.ui.UpdateBanner { nav.navigate(Dest.APP.route) }
            androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = "timeline") {
                composable("timeline") { TimelineScreen(open) }
                composable("albums") {
                    AlbumsScreen(onAlbum = { id, name -> nav.navigate("album/$id/${URLEncoder.encode(name, "UTF-8")}") },
                        onFolder = { b -> nav.navigate("folder/${URLEncoder.encode(b, "UTF-8")}") })
                }
                composable("folder/{name}") {
                    com.photohub.app.ui.FolderScreen(URLDecoder.decode(it.arguments!!.getString("name")!!, "UTF-8"), open)
                }
                composable("album/{id}/{name}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    AlbumScreen(it.arguments!!.getLong("id"), open, onDeleted = { nav.popBackStack() })
                }
                composable("map") { MapScreen(open) }
                composable("search") { SearchScreen(open) { id, name -> nav.navigate("album/$id/${URLEncoder.encode(name, "UTF-8")}") } }
                composable("shared") {
                    com.photohub.app.ui.SharedScreen(onAlbum = { id, name -> nav.navigate("album/$id/${URLEncoder.encode(name, "UTF-8")}") }, onOpen = open)
                }
                composable("account") {
                    com.photohub.app.ui.AccountHub(open = { d -> nav.navigate(d.route) },
                        onLoggedOut = { nav.navigate("setup") { popUpTo(0) } })
                }
                composable(Dest.DRIVE_BACKUP.route) { com.photohub.app.ui.DriveBackupScreen() }
                composable(Dest.SIMILAR.route) { com.photohub.app.ui.SimilarScreen() }
                composable(Dest.CLEANUP.route) { com.photohub.app.ui.GoogleCleanupScreen() }
                composable(Dest.MIGRATION.route) { com.photohub.app.ui.MigrationScreen(open = { d -> nav.navigate(d.route) }) }
                composable(Dest.IMPORT_LOG.route) { com.photohub.app.ui.ImportLogScreen() }
                composable(Dest.DEVICE.route) { com.photohub.app.ui.DeviceScreen() }
                composable(Dest.APP.route) { com.photohub.app.ui.AppScreen() }
                composable(Dest.LIBRARY.route) { com.photohub.app.ui.LibraryScreen() }
                composable(Dest.PLACES.route) { com.photohub.app.ui.SynologyPlacesScreen() }
                composable(Dest.ORGANIZE.route) { com.photohub.app.ui.OrganizeScreen(open = { r -> nav.navigate(r) }) }
                composable(Dest.TRASH.route) { com.photohub.app.ui.TrashScreen() }
                composable(Dest.FREE_UP.route) { com.photohub.app.ui.FreeUpScreen() }
                composable(Dest.TAKEOUT.route) { com.photohub.app.ui.TakeoutStatusScreen(open = { d -> nav.navigate(d.route) }) }
                composable(Dest.DUPLICATES.route) { com.photohub.app.ui.DuplicatesScreen() }
                composable(Dest.LOGS.route) { com.photohub.app.ui.LogScreen() }
                composable("suggest/{kind}") { com.photohub.app.ui.SuggestScreen(it.arguments!!.getString("kind")!!, open) }
                composable("setup") {
                    SetupScreen(setupLink, onDone = {
                        nav.navigate("timeline") { popUpTo("timeline") { inclusive = true } }
                    })
                }
                composable("viewer/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType })) {
                    ViewerScreen(it.arguments!!.getInt("i")) { nav.popBackStack() }
                }
            }
            // sync progress floats over the top edge: the photos never jump down and back up
            if (!fullScreen) com.photohub.app.ui.NasListBanner(Modifier.align(androidx.compose.ui.Alignment.TopCenter))
            }
        }
    }
}


private fun titleFor(route: String, name: String?) = when {
    route.startsWith("album/") || route.startsWith("folder/") -> name?.let { URLDecoder.decode(it, "UTF-8") } ?: "アルバム"
    route == "account" -> "設定"
    route.startsWith("suggest/") -> "消してもよさそうな写真"
    Dest.entries.any { it.route == route } -> Dest.entries.first { it.route == route }.title
    else -> tabs.firstOrNull { it.route == route }?.label ?: "PhotoHub"
}
