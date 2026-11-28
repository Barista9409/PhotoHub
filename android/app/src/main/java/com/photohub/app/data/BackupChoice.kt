package com.photohub.app.data

import android.content.Context
import com.photohub.app.data.db.LocalMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which phone folders are sent on their own (カメラ, スクリーンショット, LINE ... each on or off), and the
 * photos people chose to send anyway (「Synologyに送る」 on photos of a folder that is off).
 * Kept on this phone: the folders are this phone's.
 */
object BackupChoice {
    private const val SKIP = "skip_buckets"
    private const val FORCE = "force_ids"
    private val _skipped = MutableStateFlow<Set<String>?>(null)
    val skipped: StateFlow<Set<String>?> = _skipped

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("photohub_backup_choice", Context.MODE_PRIVATE)

    fun skippedNow(ctx: Context): Set<String> =
        _skipped.value ?: prefs(ctx).getStringSet(SKIP, emptySet())!!.toSet().also { _skipped.value = it }

    fun setFolder(ctx: Context, bucket: String, send: Boolean) {
        val now = if (send) skippedNow(ctx) - bucket else skippedNow(ctx) + bucket
        prefs(ctx).edit().putStringSet(SKIP, now).apply()
        _skipped.value = now
    }

    private fun forced(ctx: Context): Set<String> = prefs(ctx).getStringSet(FORCE, emptySet())!!.toSet()

    /** Send these phone photos even though their folder is off. */
    fun sendAnyway(ctx: Context, mediaStoreIds: Collection<Long>) {
        prefs(ctx).edit().putStringSet(FORCE, forced(ctx) + mediaStoreIds.map { it.toString() }).apply()
    }

    /** The phone photos the backup may send (folder on, or chosen by hand). */
    fun filter(ctx: Context, local: List<LocalMedia>): List<LocalMedia> {
        val skip = skippedNow(ctx)
        if (skip.isEmpty()) return local
        val force = forced(ctx)
        return local.filter { it.bucket == null || it.bucket !in skip || it.mediaStoreId.toString() in force }
    }

    /** 「Camera」「Screenshots」... as people call them. */
    fun label(bucket: String): String = when (bucket.lowercase()) {
        "camera" -> "カメラ"
        "screenshots" -> "スクリーンショット"
        "screen recordings", "screenrecorder" -> "画面録画"
        "download", "downloads" -> "ダウンロード"
        "pictures" -> "ピクチャ"
        else -> bucket
    }
}
