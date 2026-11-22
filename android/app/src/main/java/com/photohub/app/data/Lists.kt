package com.photohub.app.data

import com.photohub.app.data.db.AlbumWithCount
import com.photohub.app.data.db.AppDb
import com.photohub.app.data.db.FolderOf
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * The big lists, kept in memory for the whole app: switching tabs shows them at once instead of
 * reading 15,000+ rows again. null = still loading (not "no photos").
 */
class Lists(db: AppDb, scope: CoroutineScope) {
    private val dao = db.media()
    val timeline: StateFlow<List<TimelineItem>?> = dao.timeline().calm().stateIn(scope, SharingStarted.Eagerly, null)
    val shared: StateFlow<List<TimelineItem>?> = dao.sharedSpace().calm().stateIn(scope, SharingStarted.Eagerly, null)
    val sharedFolders: StateFlow<List<FolderOf>?> = dao.sharedFolders().calm().stateIn(scope, SharingStarted.Eagerly, null)
    val albums: StateFlow<List<AlbumWithCount>?> = dao.albums().calm().stateIn(scope, SharingStarted.Eagerly, null)
}

/**
 * The first list at once, later ones at most about once a second: while a sync writes thousands of
 * rows, the database would otherwise re-read the whole library after every page and the screen
 * would rebuild each time. Identical lists are skipped.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
private fun <T> kotlinx.coroutines.flow.Flow<T>.calm(): kotlinx.coroutines.flow.Flow<T> {
    var first = true
    return debounce { if (first) { first = false; 0L } else 800L }.distinctUntilChanged()
}
