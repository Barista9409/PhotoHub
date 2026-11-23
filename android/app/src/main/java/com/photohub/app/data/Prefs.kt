package com.photohub.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("photohub")

data class Settings(
    val lanUrl: String = "",          // e.g. http://192.168.1.10:8765
    val remoteUrl: String = "",       // Tailscale, e.g. http://100.101.102.103:8765
    val token: String = "",
    val homeSsid: String = "",
    val storageCapMb: Int = 4096,     // device storage for thumbnails + previews
    val previewMonths: Int = 6,       // previews pre-fetched for the most recent N months
    val previewAlbums: Set<String> = emptySet(),
    val cursor: Long = 0,
    val lastSyncAt: Long = 0,
    val autoUpdate: Boolean = true,   // download + install new app versions from the NAS
    // --- backup ---
    val backupNas: Boolean = true,      // send phone photos straight to the NAS
    val backupDrive: Boolean = false,   // keep recent photos in the person's Google Drive
    val driveMode: String = "months",   // months / gb / both (whichever is reached first)
    val driveMonths: Int = 3,
    val driveGb: Int = 10,
    val backupOnMobile: Boolean = false, // also back up over mobile data
) {
    val configured get() = token.isNotBlank() && (lanUrl.isNotBlank() || remoteUrl.isNotBlank())
}

class Prefs(private val ctx: Context) {
    private object K {
        val lan = stringPreferencesKey("lan")
        val remote = stringPreferencesKey("remote")
        val token = stringPreferencesKey("token")
        val ssid = stringPreferencesKey("ssid")
        val cap = intPreferencesKey("cap_mb")
        val months = intPreferencesKey("preview_months")
        val albums = stringSetPreferencesKey("preview_albums")
        val cursor = longPreferencesKey("cursor")
        val lastSync = longPreferencesKey("last_sync")
        val favorites = stringSetPreferencesKey("favorites")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val backupNas = booleanPreferencesKey("backup_nas")
        val backupDrive = booleanPreferencesKey("backup_drive")
        val driveMode = stringPreferencesKey("drive_mode")
        val driveMonths = intPreferencesKey("drive_months")
        val driveGb = intPreferencesKey("drive_gb")
        val backupOnMobile = booleanPreferencesKey("backup_mobile")
        val driveFolder = stringPreferencesKey("drive_folder")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val lastBackupMsg = stringPreferencesKey("last_backup_msg")
    }

    val settings: Flow<Settings> = ctx.store.data.map { p ->
        Settings(
            lanUrl = p[K.lan] ?: "", remoteUrl = p[K.remote] ?: "", token = p[K.token] ?: "",
            homeSsid = p[K.ssid] ?: "", storageCapMb = p[K.cap] ?: 4096, previewMonths = p[K.months] ?: 6,
            previewAlbums = p[K.albums] ?: emptySet(), cursor = p[K.cursor] ?: 0, lastSyncAt = p[K.lastSync] ?: 0,
            autoUpdate = p[K.autoUpdate] ?: true,
            backupNas = p[K.backupNas] ?: true, backupDrive = p[K.backupDrive] ?: false,
            driveMode = p[K.driveMode] ?: "months", driveMonths = p[K.driveMonths] ?: 3, driveGb = p[K.driveGb] ?: 10,
            backupOnMobile = p[K.backupOnMobile] ?: false,
        )
    }

    val favorites: Flow<Set<String>> = ctx.store.data.map { it[K.favorites] ?: emptySet() }

    suspend fun current(): Settings = settings.first()

    suspend fun save(s: Settings) = ctx.store.edit { p ->
        p[K.lan] = s.lanUrl.trim().trimEnd('/')
        p[K.remote] = s.remoteUrl.trim().trimEnd('/')
        p[K.token] = s.token.trim()
        p[K.ssid] = s.homeSsid.trim()
        p[K.cap] = s.storageCapMb
        p[K.months] = s.previewMonths
        p[K.albums] = s.previewAlbums
        p[K.autoUpdate] = s.autoUpdate
        p[K.backupNas] = s.backupNas
        p[K.backupDrive] = s.backupDrive
        p[K.driveMode] = s.driveMode
        p[K.driveMonths] = s.driveMonths
        p[K.driveGb] = s.driveGb
        p[K.backupOnMobile] = s.backupOnMobile
    }

    val lastBackup: Flow<Pair<Long, String>> = ctx.store.data.map { (it[K.lastBackupAt] ?: 0L) to (it[K.lastBackupMsg] ?: "") }

    suspend fun setLastBackup(at: Long, msg: String) = ctx.store.edit { it[K.lastBackupAt] = at; it[K.lastBackupMsg] = msg }

    suspend fun driveFolder(): String? = ctx.store.data.first()[K.driveFolder]
    suspend fun setDriveFolder(id: String?) = ctx.store.edit { if (id == null) it.remove(K.driveFolder) else it[K.driveFolder] = id }

    suspend fun setCursor(cursor: Long) = ctx.store.edit {
        it[K.cursor] = cursor
        it[K.lastSync] = System.currentTimeMillis()
    }

    suspend fun resetCursor() = ctx.store.edit { it[K.cursor] = 0 }

    suspend fun setFavorites(hashes: Set<String>) = ctx.store.edit { it[K.favorites] = hashes }

    suspend fun toggleFavorite(hash: String) = ctx.store.edit { p ->
        val cur = p[K.favorites] ?: emptySet()
        p[K.favorites] = if (hash in cur) cur - hash else cur + hash
    }
}
