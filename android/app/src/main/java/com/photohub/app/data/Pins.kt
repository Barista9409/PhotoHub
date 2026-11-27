package com.photohub.app.data

import com.photohub.app.PhotoHubApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Pinned albums: shown first on the album home, in the order they were pinned. Kept on this phone (at
 * once) and on the Synology (the person's other phones, a new install).
 */
object Pins {
    private const val KEY = "ids"
    private val _ids = MutableStateFlow<List<Long>>(emptyList())
    val ids: StateFlow<List<Long>> = _ids
    private var loaded = false

    private fun prefs(app: PhotoHubApp) = app.getSharedPreferences("photohub_pins", android.content.Context.MODE_PRIVATE)

    fun load(app: PhotoHubApp) {
        if (!loaded) {
            loaded = true
            _ids.value = (prefs(app).getString(KEY, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        }
        app.scope.launch {   // the Synology's list wins (pinned on another phone)
            try {
                val remote = app.net.api()?.pins()?.ids ?: return@launch
                if (remote != _ids.value) save(app, remote, push = false)
            } catch (_: Exception) {}
        }
    }

    fun set(app: PhotoHubApp, albumIds: Collection<Long>, on: Boolean) {
        val now = if (on) _ids.value + albumIds.filter { it !in _ids.value } else _ids.value - albumIds.toSet()
        save(app, now, push = true)
    }

    private fun save(app: PhotoHubApp, list: List<Long>, push: Boolean) {
        _ids.value = list
        prefs(app).edit().putString(KEY, list.joinToString(",")).apply()
        if (push) app.scope.launch {
            try { app.net.api()?.setPins(com.photohub.app.data.api.PinsBody(list)) } catch (_: Exception) {}
        }
    }
}
