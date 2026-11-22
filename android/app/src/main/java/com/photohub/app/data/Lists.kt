package com.photohub.app.data

import com.photohub.app.data.db.AlbumWithCount
import com.photohub.app.data.db.AppDb
import com.photohub.app.data.db.FolderOf
import com.photohub.app.data.db.TimelineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The big lists, kept in memory for the whole app: switching tabs shows them at once instead of
 * reading 15,000+ rows again. null = still loading (not "no photos").
 */
class Lists(db: AppDb, scope: CoroutineScope) {
    private val dao = db.media()
    val timeline: StateFlow<List<TimelineItem>?> = dao.timeline().stateIn(scope, SharingStarted.Eagerly, null)
    val shared: StateFlow<List<TimelineItem>?> = dao.sharedSpace().stateIn(scope, SharingStarted.Eagerly, null)
    val sharedFolders: StateFlow<List<FolderOf>?> = dao.sharedFolders().stateIn(scope, SharingStarted.Eagerly, null)
    val albums: StateFlow<List<AlbumWithCount>?> = dao.albums().stateIn(scope, SharingStarted.Eagerly, null)
}
