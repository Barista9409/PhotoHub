package com.photohub.app.data

import android.content.Context
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
    }

    val settings: Flow<Settings> = ctx.store.data.map { p ->
        Settings(
            lanUrl = p[K.lan] ?: "", remoteUrl = p[K.remote] ?: "", token = p[K.token] ?: "",
            homeSsid = p[K.ssid] ?: "", storageCapMb = p[K.cap] ?: 4096, previewMonths = p[K.months] ?: 6,
            previewAlbums = p[K.albums] ?: emptySet(), cursor = p[K.cursor] ?: 0, lastSyncAt = p[K.lastSync] ?: 0,
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
    }

    suspend fun setCursor(cursor: Long) = ctx.store.edit {
        it[K.cursor] = cursor
        it[K.lastSync] = System.currentTimeMillis()
    }

    suspend fun resetCursor() = ctx.store.edit { it[K.cursor] = 0 }

    suspend fun toggleFavorite(hash: String) = ctx.store.edit { p ->
        val cur = p[K.favorites] ?: emptySet()
        p[K.favorites] = if (hash in cur) cur - hash else cur + hash
    }
}
