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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
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
import com.photohub.app.ui.AdminScreen
import com.photohub.app.ui.AlbumScreen
import com.photohub.app.ui.AlbumsScreen
import com.photohub.app.ui.MapScreen
import com.photohub.app.ui.PhotoHubTheme
import com.photohub.app.ui.SearchScreen
import com.photohub.app.ui.SettingsScreen
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
    Tab("timeline", "写真", Icons.Default.PhotoLibrary),
    Tab("albums", "アルバム", Icons.Default.PhotoAlbum),
    Tab("map", "地図", Icons.Default.Map),
    Tab("search", "検索", Icons.Default.Search),
    Tab("admin", "管理", Icons.Default.Tune),
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
        else if (openUpdate) nav.navigate("admin")
    }
    LaunchedEffect(setupLink) { if (setupLink != null) nav.navigate("setup") }
    val open: (List<TimelineItem>, Int) -> Unit = { list, i -> UiState.viewerItems = list; nav.navigate("viewer/$i") }
    val fullScreen = route.startsWith("viewer") || route == "setup"
    Scaffold(
        topBar = {
            if (!fullScreen) TopAppBar(
                title = { Text(titleFor(route, entry?.arguments?.getString("name"))) },
                navigationIcon = {
                    if (tabs.none { it.route == route }) IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                },
                actions = {
                    val upd by app.updater.state.collectAsState()
                    if (upd is com.photohub.app.data.Updater.State.Available || upd is com.photohub.app.data.Updater.State.Ready) {
                        IconButton({ nav.navigate("admin") }) {
                            Icon(Icons.Default.SystemUpdate, "アプリの更新があります", tint = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                        }
                    }
                    if (route != "settings") IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "設定") }
                },
            )
        },
        bottomBar = {
            if (!fullScreen) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(selected = route == t.route, onClick = {
                        nav.navigate(t.route) { popUpTo("timeline") { saveState = true }; launchSingleTop = true; restoreState = true }
                    }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                }
            }
        },
    ) { pad ->
        Box(if (fullScreen) Modifier else Modifier.padding(pad)) {
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
                    AlbumScreen(it.arguments!!.getLong("id"), open)
                }
                composable("map") { MapScreen(open) }
                composable("search") { SearchScreen(open) }
                composable("admin") { AdminScreen { nav.navigate("settings") } }
                composable("settings") { SettingsScreen(onScan = { nav.navigate("setup") }) { nav.popBackStack() } }
                composable("setup") {
                    SetupScreen(setupLink, onDone = {
                        nav.navigate("timeline") { popUpTo("timeline") { inclusive = true } }
                    }, onManual = { nav.navigate("settings") })
                }
                composable("viewer/{i}", arguments = listOf(navArgument("i") { type = NavType.IntType })) {
                    ViewerScreen(it.arguments!!.getInt("i")) { nav.popBackStack() }
                }
            }
        }
    }
}

private fun titleFor(route: String, name: String?) = when {
    route.startsWith("album/") || route.startsWith("folder/") -> name?.let { URLDecoder.decode(it, "UTF-8") } ?: "アルバム"
    route == "settings" -> "設定"
    else -> tabs.firstOrNull { it.route == route }?.label ?: "写真ハブ"
}
