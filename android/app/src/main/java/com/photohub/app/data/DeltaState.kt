package com.photohub.app.data

import android.content.Context
import java.io.File

/**
 * What this phone has already told the Synology / learned from Google Drive, so each sync sends
 * and fetches only the difference ("delta"), and nothing is skipped:
 *  - cursors (a number or token the other side gives out; "everything after this")
 *  - sets of what was already sent (each item is sent once; a changed item is a new item)
 * Cleared when another person signs in or the Synology index starts over.
 */
class DeltaState(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("delta", Context.MODE_PRIVATE)
    private val dir = File(ctx.filesDir, "sent")

    fun string(key: String): String? = prefs.getString(key, null)
    fun setString(key: String, v: String?) = prefs.edit().putString(key, v).apply()
    fun long(key: String): Long = prefs.getLong(key, 0)
    fun setLong(key: String, v: Long) = prefs.edit().putLong(key, v).apply()

    /** A set of strings already sent under [name] (kept in a file; small: hashes). */
    fun sent(name: String): SentSet = SentSet(File(dir, name.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".txt"))

    fun clear() { prefs.edit().clear().apply(); dir.deleteRecursively() }

    class SentSet(private val file: File) {
        private val items: MutableSet<String> by lazy {
            if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf()
        }
        operator fun contains(s: String) = s in items
        fun <T> notSent(all: List<T>, key: (T) -> String): List<T> = all.filter { key(it) !in items }
        fun addAll(new: Collection<String>) {
            val fresh = new.filter { items.add(it) }
            if (fresh.isEmpty()) return
            file.parentFile?.mkdirs()
            file.appendText(fresh.joinToString("\n", postfix = "\n"))
        }
    }
}
