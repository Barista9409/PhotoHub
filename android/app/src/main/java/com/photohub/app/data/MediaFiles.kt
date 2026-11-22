package com.photohub.app.data

import android.content.Context
import java.io.File

/**
 * Device copies of NAS derivatives, keyed by content hash:
 *   files/thumbs/ab/<hash>.webp    always kept (all items; ~20 KB each)
 *   files/previews/ab/<hash>.webp  recent / favourite / chosen albums, evicted to the size cap
 */
class MediaFiles(ctx: Context) {
    private val thumbs = File(ctx.filesDir, "thumbs")
    private val previews = File(ctx.filesDir, "previews")
    val shareDir = File(ctx.cacheDir, "share")

    private fun safe(hash: String) = hash.replace(':', '_')

    fun thumb(hash: String) = File(thumbs, "${safe(hash).take(2)}/${safe(hash)}.webp")
    fun preview(hash: String) = File(previews, "${safe(hash).take(2)}/${safe(hash)}.webp")

    /** Which thumbnails are on the phone, kept in memory: the photo grid asks for every tile it
     *  draws, and asking the disk each time made scrolling stutter. Filled once in the background. */
    @Volatile private var known: MutableSet<String>? = null

    fun loadIndex() {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        thumbs.listFiles()?.forEach { d -> d.list()?.forEach { n -> if (n.endsWith(".webp")) set += n.removeSuffix(".webp") } }
        known = set
    }

    fun hasThumb(hash: String): Boolean = known?.contains(safe(hash)) ?: thumb(hash).exists()
    fun hasPreview(hash: String) = preview(hash).exists()

    fun writeThumb(hash: String, data: ByteArray) { atomicWrite(thumb(hash), data); known?.add(safe(hash)) }
    fun writePreview(hash: String, data: ByteArray) = atomicWrite(preview(hash), data)

    private fun atomicWrite(f: File, data: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        tmp.renameTo(f)
    }

    fun usedBytes(): Long = dirSize(thumbs) + dirSize(previews)
    fun thumbBytes(): Long = dirSize(thumbs)
    fun previewBytes(): Long = dirSize(previews)

    private fun dirSize(d: File): Long = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Keep total under the cap: drop least recently used previews not in [keep]. */
    fun evict(capBytes: Long, keep: Set<String>) {
        var used = usedBytes()
        if (used <= capBytes) return
        val files = previews.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        for (f in files) {
            if (used <= capBytes) break
            val hash = f.nameWithoutExtension
            if (hash in keep) continue
            used -= f.length()
            f.delete()
        }
        if (used > capBytes) {
            for (f in files) {
                if (used <= capBytes) break
                if (f.exists()) { used -= f.length(); f.delete() }
            }
        }
    }

    fun removeFor(hashes: Collection<String>) = hashes.forEach { thumb(it).delete(); preview(it).delete(); known?.remove(safe(it)) }

    /** Another person signs in on this phone: drop everything that came from the NAS. */
    fun clearAll() { thumbs.deleteRecursively(); previews.deleteRecursively(); clearShare(); known?.clear() }

    fun clearShare() { shareDir.deleteRecursively(); shareDir.mkdirs() }
}
