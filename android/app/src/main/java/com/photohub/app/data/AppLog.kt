package com.photohub.app.data

import android.content.Context
import com.photohub.app.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The phone's own record of how it is doing: stops, errors and how long things took.
 * Written to a small file first (so a stop is not lost), sent to the Synology after a sync,
 * and summarised there for the 「動作の記録」 page. Never sent anywhere else.
 */
object AppLog {
    @Serializable
    data class Item(val t: Long, val kind: String, val name: String, val ms: Long? = null, val msg: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private var dir: File? = null
    private val lock = Any()
    private const val MAX_LINES = 2000

    fun init(ctx: Context) { dir = ctx.filesDir }
    private fun file() = dir?.let { File(it, "applog-pending.jsonl") }

    private fun add(item: Item) {
        val f = file() ?: return
        synchronized(lock) {
            try {
                if (f.length() > 1_000_000) f.writeText(f.readLines().takeLast(MAX_LINES / 2).joinToString("\n", postfix = "\n"))
                f.appendText(json.encodeToString(Item.serializer(), item) + "\n")
            } catch (_: Exception) {}
        }
    }

    fun time(name: String, ms: Long) = add(Item(System.currentTimeMillis(), "time", name, ms = ms))
    fun error(name: String, e: Throwable? = null, msg: String? = null) =
        add(Item(System.currentTimeMillis(), "error", name, msg = msg ?: e?.let { "${it::class.java.simpleName}: ${it.message}" }))
    fun crash(e: Throwable, fatal: Boolean) =
        add(Item(System.currentTimeMillis(), if (fatal) "crash" else "error", if (fatal) "停止" else "裏の処理で失敗", msg = e.stackTraceToString().take(4000)))

    /** Measure a block and record how long it took. */
    inline fun <T> timed(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try { return block() } finally { time(name, (System.nanoTime() - t0) / 1_000_000) }
    }

    /** Send what is waiting (after a sync). Kept for the next time if the Synology does not answer. */
    suspend fun upload(api: com.photohub.app.data.api.PhotoHubApi, device: String) {
        val f = file() ?: return
        val lines = synchronized(lock) { if (f.exists()) f.readLines() else emptyList() }
        if (lines.isEmpty()) return
        val items = lines.mapNotNull { runCatching { json.decodeFromString(Item.serializer(), it) }.getOrNull() }
        for (chunk in items.chunked(500)) api.logs(com.photohub.app.data.api.LogBatch(device, BuildConfig.VERSION_NAME, chunk))
        synchronized(lock) {   // drop what was sent, keep what arrived meanwhile
            val now = if (f.exists()) f.readLines() else emptyList()
            f.writeText(now.drop(lines.size).joinToString("") { it + "\n" })
        }
    }
}
