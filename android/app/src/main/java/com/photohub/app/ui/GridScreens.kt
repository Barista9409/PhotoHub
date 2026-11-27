package com.photohub.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.photohub.app.PhotoHubApp
import com.photohub.app.data.db.TimelineItem
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.flow.first
import java.time.ZoneId

@Composable
fun AlbumsScreen(onAlbum: (Long, String) -> Unit, onFolder: (String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val loadedAlbums by app.lists.albums.collectAsState()
    val folders by app.db.media().folders().collectAsState(initial = emptyList())
    val albums = loadedAlbums ?: run { Loading(); return }
    if (albums.isEmpty() && folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("アルバムはまだありません") }
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pullMsg by remember { mutableStateOf<String?>(null) }
    // a phone folder is also kept as a Synology album (so other phones see it): show it once, as the folder
    val localBuckets = remember(folders) { folders.map { it.bucket }.toSet() }
    val folderAlbums = remember(albums) { albums.filter { it.origin == "device" }.associateBy { it.name } }
    // マイフォト: the person's own albums only (the family's are on ファミリー), one tile per kind
    val groups = remember(albums, localBuckets) { albumGroups(albums.filter { !it.shared }, family = false, phoneFolders = localBuckets) }
    val shownAlbums = groups.flatMap { it.albums }
    var openGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // すべて選択: the albums of the page on screen
    val selectable = groups.firstOrNull { it.key == openGroup }?.ordered() ?: shownAlbums
    var mergeOf by remember { mutableStateOf<List<com.photohub.app.data.db.AlbumWithCount>?>(null) }
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var folderMenu by remember { mutableStateOf<String?>(null) }
    val phoneDelete = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) {
        scope.launch { app.indexer.scan() }   // the folder list follows what is left on the phone
    }
    var askDelete by remember { mutableStateOf(false) }
    val picking = picked.isNotEmpty()
    fun card(a: com.photohub.app.data.db.AlbumWithCount): @Composable () -> Unit = {
        AlbumCard(a, selected = if (picking) a.id in picked else null,
            onLongClick = { picked = picked + a.id }) {
            if (picking) picked = if (a.id in picked) picked - a.id else picked + a.id else onAlbum(a.id, a.name)
        }
    }
    Column(Modifier.fillMaxSize()) {
    if (picking) androidx.compose.material3.Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton({ picked = emptySet() }) { androidx.compose.material3.Icon(Icons.Default.Close, "選択をやめる") }
            Text("${picked.size} 件のアルバムを選択", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            val all = picked.size == selectable.size && selectable.all { it.id in picked }
            androidx.compose.material3.IconButton({ picked = if (all) emptySet() else selectable.map { it.id }.toSet() }) {
                androidx.compose.material3.Icon(if (all) Icons.Default.Deselect else Icons.Default.SelectAll, if (all) "すべて解除" else "すべて選択")
            }
            if (picked.size >= 2) androidx.compose.material3.IconButton({ mergeOf = shownAlbums.filter { it.id in picked } }) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.MergeType, "アルバムを1つにまとめる")
            }
            androidx.compose.material3.IconButton({ askDelete = true }) { androidx.compose.material3.Icon(Icons.Default.Delete, "アルバムを削除") }
            AlbumPickMenu(shownAlbums.filter { it.id in picked }, report = { pullMsg = it }, clear = { picked = emptySet() })
        }
    }
    @Composable fun PhoneFolder(f: com.photohub.app.data.db.FolderCount) {
            Column(Modifier.padding(4.dp).combinedClickable(onClick = { onFolder(f.bucket) }, onLongClick = { folderMenu = f.bucket })) {
                Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
                    AsyncImage(model = f.coverUri?.let { android.net.Uri.parse(it) }, contentDescription = f.bucket,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Text(f.bucket, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text("${f.count} 枚" + if (f.bucket in folderAlbums) "・Synologyにも保存" else "", style = MaterialTheme.typography.bodySmall)
            }
    }
    if (openGroup == "phone") {
        // このスマホのフォルダ: a page like the other groups
        androidx.activity.compose.BackHandler { openGroup = null }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton({ openGroup = null }) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "アルバムの一覧へ") }
            Text("このスマホのフォルダ（${folders.size}）", style = MaterialTheme.typography.titleMedium)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(110.dp), modifier = Modifier.fillMaxSize().padding(4.dp)) {
            items(folders, key = { "f${it.bucket}" }) { f -> PhoneFolder(f) }
        }
    } else AlbumGroupsView(groups, openGroup, { openGroup = it }, card = ::card,
        header = {
            Column(Modifier.padding(8.dp)) {
                androidx.compose.material3.OutlinedButton({
                    pullMsg = "読み込んでいます…"
                    scope.launch {
                        pullMsg = try {
                            val r = app.net.api()!!.pullAlbums()
                            r["error"]?.toString()?.trim('"') ?: "Synology Photosのアルバム ${r["albums"]} 件を読み込みました"
                        } catch (e: Exception) { "読み込めませんでした（${e.message}）" }
                        com.photohub.app.data.SyncWorker.runNow(app)
                    }
                }) { Text("Synology Photosのアルバムを読み込む") }
                pullMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val cov by androidx.compose.runtime.produceState<List<Int>?>(null) {
                    value = try { app.net.api()?.status()?.albumCoverage } catch (_: Exception) { null }
                }
                cov?.takeIf { it.size == 2 && it[1] > 0 }?.let { (inAlbum, all) ->
                    Text("アルバムに入っている写真 ${inAlbum * 100 / all}%（${"%,d".format(inAlbum)} / ${"%,d".format(all)} 枚）",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text("おまかせアルバムはSynologyが毎晩作ります。アルバムを長押しで選ぶと、まとめる・削除・ファミリーへ などができます。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        // newest albums (made last) first, the phone's own folders left out
        recent = remember(albums) { albums.filter { !it.shared && it.origin != "device" }.sortedByDescending { it.id }.take(12) },
        extraTiles = {
            if (folders.isNotEmpty()) GroupTile("このスマホのフォルダ", "${folders.size} 件", emptyList(),
                folders.mapNotNull { it.coverUri }.take(4)) { openGroup = "phone" }
        })
    }
    folderMenu?.let { bucket ->
        val asAlbum = albums.firstOrNull { it.origin == "device" && it.name == bucket }
        androidx.compose.material3.AlertDialog(onDismissRequest = { folderMenu = null },
            title = { Text("「$bucket」フォルダ") },
            text = { Text("・Synologyのアルバムから外す：Synologyに作った「$bucket」アルバムを消し、これからも作りません。写真は消えません。\n" +
                "・スマホから消す：このフォルダの写真をスマホから消します（Androidの確認が出ます）。Synologyにある分は残ります。") },
            confirmButton = { Column(horizontalAlignment = Alignment.End) {
                androidx.compose.material3.TextButton(enabled = asAlbum != null, onClick = {
                    folderMenu = null
                    scope.launch {
                        pullMsg = try { app.net.api()!!.deleteAlbum(asAlbum!!.id); "「$bucket」アルバムを外しました" }
                                  catch (e: Exception) { "外せませんでした（${e.message}）" }
                        com.photohub.app.data.SyncWorker.runNow(app)
                    }
                }) { Text(if (asAlbum != null) "Synologyのアルバムから外す" else "Synologyにアルバムはありません") }
                androidx.compose.material3.TextButton({
                    folderMenu = null
                    scope.launch {
                        val uris = app.db.media().folder(bucket).first().mapNotNull { it.localUri?.let(android.net.Uri::parse) }
                        if (uris.isEmpty()) { pullMsg = "スマホに写真がありません"; return@launch }
                        val req = android.provider.MediaStore.createDeleteRequest(app.contentResolver, uris)
                        phoneDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(req.intentSender).build())
                    }
                }) { Text("スマホから消す", color = MaterialTheme.colorScheme.error) }
            } },
            dismissButton = { androidx.compose.material3.TextButton({ folderMenu = null }) { Text("やめる") } })
    }
    mergeOf?.let { list ->
        var name by remember { mutableStateOf(list.first().name) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { mergeOf = null },
            title = { Text("${list.size} 件のアルバムを1つにまとめる") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("写真をすべて1つのアルバムに入れ、ほかのアルバムは消します。写真は消えません。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("まとめたアルバムの名前") }, singleLine = true)
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = {
                val ids = list.map { it.id }
                mergeOf = null; picked = emptySet()
                scope.launch {
                    pullMsg = try { app.net.api()!!.mergeAlbums(com.photohub.app.data.api.AlbumMergeBody(ids, name.trim())); "「${name.trim()}」にまとめました" }
                              catch (e: retrofit2.HttpException) { if (e.code() == 404) "Synology側が古い版です" else "まとめられませんでした（HTTP ${e.code()}）" }
                              catch (e: Exception) { "まとめられませんでした（${e.message}）" }
                    com.photohub.app.data.SyncWorker.runNow(app)
                }
            }) { Text("まとめる") } },
            dismissButton = { androidx.compose.material3.TextButton({ mergeOf = null }) { Text("やめる") } })
    }
    if (askDelete) {
        val chosen = albums.filter { it.id in picked }
        val photos = chosen.sumOf { it.count }
        fun run(withPhotos: Boolean) {
            askDelete = false
            val ids = chosen.map { it.id }
            picked = emptySet()
            scope.launch {
                val api = app.net.api()
                pullMsg = if (api == null) "Synologyにつながりません" else try {
                    var trashed = 0
                    if (withPhotos) {
                        val hs = app.db.media().albumHashes(ids)
                        hs.chunked(500).forEach { trashed += api.deleteMedia(com.photohub.app.data.api.HashesBody(it))["trashed"] ?: 0 }
                    }
                    var gone = 0
                    ids.forEach { id -> try { api.deleteAlbum(id); gone++ } catch (_: Exception) {} }
                    "アルバム $gone 件を削除しました" + if (withPhotos) "（写真 $trashed 件をゴミ箱へ）" else ""
                } catch (e: Exception) { "削除できませんでした（${e.message}）" }
                com.photohub.app.data.SyncWorker.runNow(app)
            }
        }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askDelete = false },
            title = { Text(if (chosen.size == 1) "「${chosen[0].name}」を削除しますか？" else "アルバム ${chosen.size} 件を削除しますか？") },
            text = { Text("「アルバムだけ」は入れ物だけを消し、写真は残ります。" +
                "「写真も」は中の写真 $photos 件もSynologyのゴミ箱に移します（30日以内なら戻せます。ほかのアルバムに入っている写真も消えます）。" +
                if (chosen.any { it.origin == "synology" }) "Synology Photosのアルバムも消えます。" else "") },
            confirmButton = { Row {
                androidx.compose.material3.TextButton({ run(true) }) { Text("写真も削除", color = MaterialTheme.colorScheme.error) }
                androidx.compose.material3.TextButton({ run(false) }) { Text("アルバムだけ") }
            } },
            dismissButton = { androidx.compose.material3.TextButton({ askDelete = false }) { Text("やめる") } })
    }
}

@Composable
private fun SectionHead(title: String, sub: String) {
    Column(Modifier.padding(8.dp, 16.dp, 8.dp, 4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun AlbumCard(a: com.photohub.app.data.db.AlbumWithCount, modifier: Modifier = Modifier, selected: Boolean? = null,
              onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Column(modifier.padding(4.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))
            .then(if (selected == true) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier)) {
            val h = a.coverHash
            if (h != null) {
                val rots by UiState.rots.collectAsState()
                val model: Any? = remember(h, rots[h], base) { hashThumb(app, h, base) }
                AsyncImage(model = model, contentDescription = a.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (selected != null) androidx.compose.material3.Icon(
                if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("${a.count} 枚" + when (a.origin) { "google" -> "・Googleフォトから"; "device" -> "・スマホのフォルダ"; "digest", "trip", "folder", "person", "place", "growth", "favorite", "event", "daily", "kind" -> "・おまかせ"; else -> "" },
            style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 共有: what the family shares, three ways - by folder (as on the Synology), by album, or all photos.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SharedScreen(onAlbum: (Long, String) -> Unit, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val albums by app.lists.albums.collectAsState()
    val items by app.lists.shared.collectAsState()
    val folderOf by app.lists.sharedFolders.collectAsState()
    var mode by rememberSaveable { mutableStateOf("album") }
    var path by rememberSaveable { mutableStateOf("") }
    val all = items
    val fo = folderOf
    if (all == null || fo == null || albums == null) { Loading(); return }
    val family = albums!!.filter { it.shared }
    if (family.isEmpty() && all.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("家族の共有スペースに写真はまだありません。\nSynology Photosの「共有スペース」（共有フォルダ photo）に入れた写真と、家族で共有したアルバムがここに出ます。")
        }
        return
    }
    // the switch sits above everything and never moves, whichever view is chosen
    Column(Modifier.fillMaxSize()) {
    ViewSwitch(listOf("album" to "アルバム", "folder" to "フォルダ", "all" to "すべての写真"), mode) { mode = it; path = "" }
    Box(Modifier.weight(1f)) {
    when (mode) {
        "album" -> Column(Modifier.fillMaxSize()) {
            if (family.isEmpty()) Text("家族の共有アルバムはまだありません", modifier = Modifier.padding(24.dp))
            SelectableAlbumGrid(family, onAlbum)
        }
        "all" -> MediaGrid(all, onOpen, zoomBar = true, refreshable = true)
        else -> {
            // folders one level below `path`, each with its photo count (including subfolders) and newest photo
            val byHash = remember(all) { all.associateBy { it.hash } }
            val prefix = if (path.isEmpty()) "" else "$path/"
            val children = remember(fo, path) {
                fo.filter { it.folder.startsWith(prefix) && it.folder.length > prefix.length }
                    .groupBy { it.folder.removePrefix(prefix).substringBefore('/') }
                    .map { (name, list) -> Triple(name, list.size, list.maxByOrNull { it.takenAt }?.hash) }
                    .sortedBy { it.first.lowercase() }
            }
            val here = remember(fo, all, path) {
                val hs = fo.filter { it.folder == path }.map { it.hash }.toSet()
                all.filter { it.hash in hs }
            }
            MediaGrid(here, onOpen, zoomBar = true, header = {
                Column {
                    // where we are: 共有スペース > 2024 > 沖縄
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val parts = if (path.isEmpty()) emptyList() else path.split('/')
                        Text("共有スペース", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { path = "" })
                        parts.forEachIndexed { i, p ->
                            Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(p, color = if (i == parts.lastIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                                maxLines = 1, modifier = Modifier.clickable { path = parts.take(i + 1).joinToString("/") })
                        }
                    }
                    androidx.compose.foundation.layout.FlowRow(Modifier.padding(4.dp)) {
                        children.forEach { (name, n, cover) ->
                            FolderCard(name, n, cover?.let { byHash[it] }, Modifier.width(120.dp)) {
                                path = if (path.isEmpty()) name else "$path/$name"
                            }
                        }
                    }
                    if (here.isNotEmpty()) SectionHead("このフォルダの写真", "${here.size} 件")
                    else if (children.isEmpty()) Text("このフォルダに写真はありません", modifier = Modifier.padding(16.dp))
                }
            })
        }
    }
    }
    }
}

/** The view switch at the very top of a tab (アルバム / フォルダ / すべての写真): fixed, it never scrolls away. */
@Composable
fun ViewSwitch(options: List<Pair<String, String>>, current: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp, 8.dp), horizontalArrangement = Arrangement.Center) {
        Segmented(options, current, onChange)
    }
}

/**
 * The フォト tab: the person's albums and all their photos in one place, with the same fixed switch
 * as the 共有 tab. It opens on the albums.
 */
@Composable
fun PhotosHome(open: (List<TimelineItem>, Int) -> Unit, onAlbum: (Long, String) -> Unit, onFolder: (String) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("album") }
    Column(Modifier.fillMaxSize()) {
        ViewSwitch(listOf("album" to "アルバム", "all" to "すべての写真"), mode) { mode = it }
        Box(Modifier.weight(1f)) {
            if (mode == "album") AlbumsScreen(onAlbum = onAlbum, onFolder = onFolder) else TimelineScreen(open)
        }
    }
}

@Composable
private fun FolderCard(name: String, count: Int, cover: TimelineItem?, modifier: Modifier, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Column(modifier.padding(4.dp).clickable(onClick = onClick)) {
        Box(Modifier.aspectRatio(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22888888))) {
            if (cover != null) AsyncImage(model = thumbModel(app, cover, base), contentDescription = name,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Icon(Icons.Default.Folder, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(2.dp))
        }
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("${count} 件", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FolderScreen(bucket: String, onOpen: (List<TimelineItem>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val items by app.db.media().folder(bucket).collectAsState(initial = emptyList())
    MediaGrid(items, onOpen)
}

@Composable
fun AlbumScreen(albumId: Long, onOpen: (List<TimelineItem>, Int) -> Unit, onDeleted: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val items by app.db.media().album(albumId).collectAsState(initial = emptyList())
    val album = (app.lists.albums.collectAsState().value ?: emptyList()).firstOrNull { it.id == albumId }
    var rename by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var spaceOf by remember { mutableStateOf<Boolean?>(null) }   // the album to ファミリー (true) / マイフォト (false)
    var download by remember { mutableStateOf(false) }
    var askBirthday by remember { mutableStateOf(false) }
    if (askBirthday) {
        val st = androidx.compose.material3.rememberDatePickerState()
        androidx.compose.material3.DatePickerDialog(onDismissRequest = { askBirthday = false },
            confirmButton = { androidx.compose.material3.TextButton(enabled = st.selectedDateMillis != null, onClick = {
                askBirthday = false
                val day = java.time.Instant.ofEpochMilli(st.selectedDateMillis!!).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                scope.launch {
                    val msg = try { val r = app.net.api()!!.setBirthday(albumId, com.photohub.app.data.api.BirthdayBody(day))
                        "誕生日を $day にしました。成長アルバムを ${r["albums"] ?: 0} 件作りました" }
                        catch (e: retrofit2.HttpException) { if (e.code() == 404) "Synology側が古い版です" else "設定できませんでした（HTTP ${e.code()}）" }
                        catch (e: Exception) { "設定できませんでした（${e.message}）" }
                    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
                    com.photohub.app.data.SyncWorker.runNow(ctx)
                }
            }) { Text("この日にする") } },
            dismissButton = { androidx.compose.material3.TextButton({ askBirthday = false }) { Text("やめる") } }) {
            androidx.compose.material3.DatePicker(st, title = { Text("${album?.name ?: ""}の誕生日", Modifier.padding(16.dp)) })
        }
    }
    MediaGrid(items, onOpen, albumId = albumId, header = {
        Row(Modifier.fillMaxWidth().padding(12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(if (album?.shared == true) "家族で共有" else "自分だけ",
                if (album?.origin == "synology") "Synology Photosと同期" else null, "${items.size} 件").joinToString("・"),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            // the slideshow is the main thing to do with an album; the rest sits in the ︙ menu
            androidx.compose.material3.FilledTonalButton(enabled = items.isNotEmpty(), onClick = {
                UiState.slideshow = true
                onOpen(items.reversed(), 0)   // oldest first: the story in order
            }) { Icon(Icons.Default.PlayArrow, null); Text("スライドショー") }
            var menu by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "ほかの操作") }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (album?.origin == "person") androidx.compose.material3.DropdownMenuItem(text = { Text("誕生日を設定（成長アルバム）") },
                        leadingIcon = { Icon(Icons.Default.Cake, null) }, onClick = { menu = false; askBirthday = true })
                    val toFamily = album?.shared != true
                    androidx.compose.material3.DropdownMenuItem(text = { Text(if (toFamily) "ファミリーへ…" else "マイフォトへ…") },
                        leadingIcon = { Icon(Icons.Default.FamilyRestroom, null) }, onClick = { menu = false; spaceOf = toFamily })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("ダウンロード") }, leadingIcon = { Icon(Icons.Default.Download, null) },
                        onClick = { menu = false; download = true })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("名前を変える") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; rename = album?.name ?: "" })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("削除") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    })
    rename?.let { cur ->
        var v by remember { mutableStateOf(cur) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { rename = null }, title = { Text("アルバムの名前") },
            text = { OutlinedTextField(v, { v = it }, singleLine = true) },
            confirmButton = { androidx.compose.material3.TextButton(enabled = v.isNotBlank(), onClick = {
                rename = null
                scope.launch { try { app.net.api()!!.renameAlbum(albumId, com.photohub.app.data.api.AlbumNameBody(v.trim())) } catch (_: Exception) {}
                    com.photohub.app.data.SyncWorker.runNow(ctx) }
            }) { Text("変える") } },
            dismissButton = { androidx.compose.material3.TextButton({ rename = null }) { Text("やめる") } })
    }
    if (download) DownloadDialog(items, album?.name ?: "アルバム") { msg ->
        download = false
        msg?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    spaceOf?.let { fam ->
        SpaceDialog("「${album?.name ?: "アルバム"}」を${if (fam) "ファミリー" else "マイフォト"}へ", fam, chooseDest = false, album = true,
            onDismiss = { spaceOf = null }) { to, how ->
            spaceOf = null
            scope.launch {
                android.widget.Toast.makeText(ctx, runAlbumSpace(app, albumId, to, how), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    if (confirmDelete) androidx.compose.material3.AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("アルバムを削除しますか？") },
        text = { Text("アルバムだけを消します。中の写真は消えません。" + if (album?.origin == "synology") "Synology Photosのアルバムも消えます。" else "") },
        confirmButton = { androidx.compose.material3.TextButton({
            confirmDelete = false
            scope.launch { try { app.net.api()!!.deleteAlbum(albumId) } catch (_: Exception) {}
                com.photohub.app.data.SyncWorker.runNow(ctx); onDeleted() }
        }) { Text("削除") } },
        dismissButton = { androidx.compose.material3.TextButton({ confirmDelete = false }) { Text("やめる") } })
}

/** What the search box understands: words, a year ("2023"), a month ("2023年8月", "2023/8"). */
private data class Query(val text: String, val year: Int?, val month: Int?)

private fun parse(q: String): Query {
    val m = Regex("""(19|20)\d{2}(?:\s*[年/\-.]\s*(\d{1,2})\s*月?)?\s*年?""").find(q)
    val year = m?.value?.take(4)?.toIntOrNull()
    val month = m?.groupValues?.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1..12 }
    val text = (if (m != null) q.removeRange(m.range) else q).trim()
    return Query(text, year, month)
}

/**
 * Search: a start page with ways to look (kinds, years, albums), then results.
 * Everything runs on the phone (works offline too).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(onOpen: (List<TimelineItem>, Int) -> Unit, onAlbum: (Long, String) -> Unit = { _, _ -> }) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    var q by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    var withPlace by rememberSaveable { mutableStateOf(false) }
    var favOnly by rememberSaveable { mutableStateOf(false) }
    var shots by rememberSaveable { mutableStateOf(false) }
    val parsed = remember(q) { parse(q) }
    val active = q.isNotBlank() || kind != "" || withPlace || favOnly || shots
    val zone = ZoneId.systemDefault()
    val from = parsed.year?.let { y -> LocalDate.of(y, parsed.month ?: 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
    val to = parsed.year?.let { y ->
        val start = LocalDate.of(y, parsed.month ?: 1, 1)
        (if (parsed.month != null) start.plusMonths(1) else start.plusYears(1)).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    } ?: Long.MAX_VALUE
    val raw by remember(parsed, kind, withPlace) {
        app.db.media().search(parsed.text, from, to, kind, if (withPlace) 1 else 0)
    }.collectAsState(initial = emptyList())
    val favs by app.prefs.favorites.collectAsState(initial = emptySet())
    val all = app.lists.timeline.collectAsState().value ?: emptyList()
    // what is in the photos ("犬", "海" ...): Synology Photos' recognition, asked on the Synology
    var tagHits by remember { mutableStateOf<Set<String>>(emptySet()) }
    var tagSuggest by remember { mutableStateOf<List<com.photohub.app.data.api.TagCount>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(parsed.text) {
        kotlinx.coroutines.delay(300)
        val r = try { app.net.api()?.searchTags(parsed.text) } catch (_: Exception) { null }
        tagHits = r?.hashes?.toSet() ?: emptySet()
        if (parsed.text.isBlank()) tagSuggest = r?.tags ?: emptyList()
    }
    val results = remember(raw, favOnly, shots, favs, tagHits, all) {
        val byTag = if (tagHits.isEmpty()) emptyList() else all.filter { it.hash != null && it.hash in tagHits &&
            it.takenAt in from..to && (kind == "" || it.kind == kind) && (!withPlace || it.lat != null) }
        (raw + byTag).distinctBy { it.itemKey }.sortedByDescending { it.takenAt }
            .filter { (!favOnly || (it.hash != null && it.hash in favs)) && (!shots || it.name.contains("screenshot", ignoreCase = true)) }
    }
    val years = remember(all) {
        all.groupBy { java.time.Instant.ofEpochMilli(it.takenAt).atZone(zoneFor(it.tz)).year }.toSortedMap(compareByDescending { it })
    }
    val albums = app.lists.albums.collectAsState().value ?: emptyList()
    val places by app.db.media().places().collectAsState(initial = emptyList())

    // opened from the magnifier: ready to type at once
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        if (q.isEmpty()) try { focus.requestFocus() } catch (_: Exception) {}
    }
    val box: @Composable () -> Unit = {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(q, { q = it }, placeholder = { Text("例：犬、海、渋谷、2023年8月、メモの言葉") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (active) androidx.compose.material3.IconButton({ q = ""; kind = ""; withPlace = false; favOnly = false; shots = false }) {
                    Icon(Icons.Default.Close, "条件を消す") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "video", { kind = if (kind == "video") "" else "video" }, label = { Text("動画") })
                FilterChip(kind == "photo", { kind = if (kind == "photo") "" else "photo" }, label = { Text("写真だけ") })
                FilterChip(favOnly, { favOnly = !favOnly }, label = { Text("お気に入り") })
                FilterChip(withPlace, { withPlace = !withPlace }, label = { Text("場所あり") })
                FilterChip(shots, { shots = !shots }, label = { Text("スクリーンショット") })
            }
            // suggestions from what Synology Photos recognised in the photos
            if (q.isBlank() && tagSuggest.isNotEmpty()) {
                Text("写っているもので探す", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tagSuggest.take(20).forEach { t ->
                        androidx.compose.material3.AssistChip({ q = t.name }, label = { Text("${t.name} ${t.count}") })
                    }
                }
            }
            if (active) {
                val cond = buildList {
                    parsed.year?.let { add(if (parsed.month != null) "${it}年${parsed.month}月" else "${it}年") }
                    if (parsed.text.isNotBlank()) add("「${parsed.text}」（写っているもの・場所・ファイル名・アルバム名・メモ）")
                }
                Text((if (cond.isEmpty()) "" else cond.joinToString("・") + "　") + "${results.size} 件",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (active) { MediaGrid(results, onOpen, header = box); return }

    // start page: ways to look
    LazyColumn(Modifier.fillMaxSize()) {
        item { box() }
        item {
            Text("ことばは、撮った場所（市区町村）・ファイル名・アルバム名・写真の説明から探します。年や「2023年8月」も入れられます。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (years.isNotEmpty()) {
            item { Text("年から探す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(years.entries.toList(), key = { it.key }) { (y, list) ->
                        CoverTile("${y}年", "${list.size} 件", list.first()) { q = "${y}年" }
                    }
                }
            }
        }
        if (places.isNotEmpty()) {
            item { Text("場所から探す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp)) }
            item {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    places.forEach { p ->
                        androidx.compose.material3.AssistChip(onClick = { q = p.place }, label = { Text("${p.place}（${p.n}）") })
                    }
                }
            }
        }
        if (albums.isNotEmpty()) {
            item { Text("アルバムから探す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 20.dp, 16.dp, 8.dp)) }
            item {
                androidx.compose.foundation.lazy.LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    items(albums, key = { it.id }) { a -> AlbumCard(a, Modifier.width(130.dp)) { onAlbum(a.id, a.name) } }
                }
            }
        }
    }
}

@Composable
private fun CoverTile(title: String, sub: String, cover: TimelineItem, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val base by UiState.base.collectAsState()
    Box(Modifier.width(120.dp).aspectRatio(0.8f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        AsyncImage(model = thumbModel(app, cover, base), contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color(0x44000000)))
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(sub, color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun <T> LazyRowChips(values: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(values) { v -> FilterChip(v == selected, { onPick(v) }, label = { Text(label(v)) }) }
    }
}

@Composable
fun SimpleList(lines: List<Pair<String, String>>) {
    LazyColumn { items(lines) { (a, b) -> ListItem(headlineContent = { Text(a) }, supportingContent = { Text(b) }) } }
}

/** Put the chosen photos in an album: an existing one (own or family) or a new one. */
@Composable
fun AddToAlbumDialog(items: List<TimelineItem>, onDone: (String?) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as PhotoHubApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val albums = app.lists.albums.collectAsState().value ?: emptyList()
    var newName by remember { mutableStateOf("") }
    var shared by remember { mutableStateOf(false) }
    val hashes = items.mapNotNull { it.hash }
    fun finish(block: suspend () -> String) = scope.launch {
        val msg = try { block() } catch (e: Exception) { "できませんでした（${e.message}）" }
        com.photohub.app.data.SyncWorker.runNow(ctx)
        onDone(msg)
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = { onDone(null) },
        title = { Text("${hashes.size} 件をアルバムに追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hashes.isEmpty()) Text("Synologyにある写真が選ばれていません（スマホにしかない写真は、同期したあとで追加できます）。")
                OutlinedTextField(newName, { newName = it }, label = { Text("新しいアルバムの名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(shared, { shared = it })
                    Text("家族で共有するアルバムにする", style = MaterialTheme.typography.bodySmall)
                }
                if (albums.isNotEmpty()) Text("または、今あるアルバムに追加", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(albums, key = { it.id }) { a ->
                        Text(a.name + if (a.shared) "（家族）" else "", modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = hashes.isNotEmpty()) {
                                finish { app.net.api()!!.addToAlbum(a.id, com.photohub.app.data.api.HashesBody(hashes)); "「${a.name}」に追加しました" }
                            }.padding(vertical = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = newName.isNotBlank(), onClick = {
                finish { app.net.api()!!.createAlbum(com.photohub.app.data.api.AlbumNewBody(newName.trim(), hashes, shared)); "アルバム「${newName.trim()}」を作りました" }
            }) { Text("新しいアルバムを作る") }
        },
        dismissButton = { androidx.compose.material3.TextButton({ onDone(null) }) { Text("やめる") } })
}


/**
 * A grid of albums where a long press starts choosing: then 「1つにまとめる」 (two or more) and
 * 「削除」 (the albums only; photos stay). Used on the 共有 tab's album view.
 */
@Composable
fun SelectableAlbumGrid(albums: List<com.photohub.app.data.db.AlbumWithCount>, onAlbum: (Long, String) -> Unit) {
    val app = LocalContext.current.applicationContext as PhotoHubApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var mergeOf by remember { mutableStateOf<List<com.photohub.app.data.db.AlbumWithCount>?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val picking = picked.isNotEmpty()
    // like マイフォト: one tile per kind, each opening a page of its own
    val groups = remember(albums) { albumGroups(albums, family = true) }
    var openGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val selectable = groups.firstOrNull { it.key == openGroup }?.ordered() ?: albums
    Column(Modifier.fillMaxSize()) {
        if (picking) androidx.compose.material3.Surface(tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.IconButton({ picked = emptySet() }) { Icon(Icons.Default.Close, "選択をやめる") }
                Text("${picked.size} 件", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                val all = picked.size == selectable.size && selectable.all { it.id in picked }
                androidx.compose.material3.TextButton({ picked = if (all) emptySet() else selectable.map { it.id }.toSet() }) {
                    Text(if (all) "すべて解除" else "すべて選択")
                }
                if (picked.size >= 2) androidx.compose.material3.IconButton({ mergeOf = albums.filter { it.id in picked } }) {
                    Icon(Icons.AutoMirrored.Filled.MergeType, "1つにまとめる")
                }
                androidx.compose.material3.IconButton({ askDelete = true }) { Icon(Icons.Default.Delete, "アルバムを削除", tint = MaterialTheme.colorScheme.error) }
                AlbumPickMenu(albums.filter { it.id in picked }, report = { msg = it }, clear = { picked = emptySet() })
            }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp, 4.dp)) }
        if (!picking) Text("長押しで選ぶと、まとめる・削除・ファミリー／マイフォトへ・ダウンロードができます", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp, 4.dp))
        AlbumGroupsView(groups, openGroup, { openGroup = it }, card = { a -> {
            AlbumCard(a, selected = if (picking) a.id in picked else null, onLongClick = { picked = picked + a.id }) {
                if (picking) picked = if (a.id in picked) picked - a.id else picked + a.id else onAlbum(a.id, a.name)
            }
        } })
    }
    mergeOf?.let { list ->
        var name by remember { mutableStateOf(list.first().name) }
        androidx.compose.material3.AlertDialog(onDismissRequest = { mergeOf = null },
            title = { Text("${list.size} 件のアルバムを1つにまとめる") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("写真をすべて1つのアルバムに入れ、ほかのアルバムは消します。写真は消えません。家族みんなに反映されます。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("まとめたアルバムの名前") }, singleLine = true)
            } },
            confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = {
                val ids = list.map { it.id }
                mergeOf = null; picked = emptySet()
                scope.launch {
                    msg = try { app.net.api()!!.mergeAlbums(com.photohub.app.data.api.AlbumMergeBody(ids, name.trim())); "「${name.trim()}」にまとめました" }
                          catch (e: retrofit2.HttpException) { if (e.code() == 404) "Synology側が古い版です" else "まとめられませんでした（HTTP ${e.code()}）" }
                          catch (e: Exception) { "まとめられませんでした（${e.message}）" }
                    com.photohub.app.data.SyncWorker.runNow(app)
                }
            }) { Text("まとめる") } },
            dismissButton = { androidx.compose.material3.TextButton({ mergeOf = null }) { Text("やめる") } })
    }
    if (askDelete) {
        val chosen = albums.filter { it.id in picked }
        androidx.compose.material3.AlertDialog(onDismissRequest = { askDelete = false },
            title = { Text(if (chosen.size == 1) "「${chosen[0].name}」を削除しますか？" else "アルバム ${chosen.size} 件を削除しますか？") },
            text = { Text("アルバムだけを消します。写真は消えません。家族みんなのアルバムから消えます。") },
            confirmButton = { androidx.compose.material3.TextButton({
                askDelete = false
                val ids = chosen.map { it.id }
                picked = emptySet()
                scope.launch {
                    var gone = 0
                    ids.forEach { id -> try { app.net.api()!!.deleteAlbum(id); gone++ } catch (_: Exception) {} }
                    msg = "アルバム $gone 件を削除しました"
                    com.photohub.app.data.SyncWorker.runNow(app)
                }
            }) { Text("削除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { androidx.compose.material3.TextButton({ askDelete = false }) { Text("やめる") } })
    }
}

