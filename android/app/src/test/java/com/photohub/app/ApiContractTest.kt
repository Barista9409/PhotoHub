package com.photohub.app

import com.photohub.app.data.LocalIndexer
import com.photohub.app.data.api.Candidate
import com.photohub.app.data.api.Job
import com.photohub.app.data.api.SimilarGroup
import com.photohub.app.data.api.Status
import com.photohub.app.data.api.SyncPage
import com.photohub.app.data.api.ThumbPack
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodes responses recorded from the real PhotoHub server (server/photohub/api.py). */
class ApiContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!

    @Test fun syncPage() {
        val page = json.decodeFromString<SyncPage>(res("sync.json").bufferedReader().readText())
        assertEquals(4, page.items.size)
        assertFalse(page.hasMore)
        assertEquals(page.items.maxOf { it.rev }, page.cursor)
        val first = page.items.first()
        assertEquals("photo", first.kind)
        assertEquals(64, first.fileHash.length)
        assertTrue("nas" in first.stores)
    }

    @Test fun statusJobsSimilarCandidates() {
        val st = json.decodeFromString<Status>(res("status.json").bufferedReader().readText())
        assertEquals(4, st.photos)
        assertEquals("reo", st.user)
        assertTrue(st.admin)
        val me = json.decodeFromString<com.photohub.app.data.api.Me>(res("me.json").bufferedReader().readText())
        assertEquals("reo", me.name)
        val jobs = json.decodeFromString<List<Job>>(res("jobs.json").bufferedReader().readText())
        assertTrue(jobs.isNotEmpty())
        val groups = json.decodeFromString<List<SimilarGroup>>(res("similar.json").bufferedReader().readText())
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].members.size)
        assertEquals("shared", groups[0].space)
        json.decodeFromString<List<Candidate>>(res("candidates.json").bufferedReader().readText())
    }

    @Test fun thumbPack() {
        val hashes = res("thumbs.hashes").bufferedReader().readLines()
        val got = mutableMapOf<String, ByteArray>()
        ThumbPack.read(res("thumbs.bin")) { h, d -> got[h] = d }
        assertEquals(hashes.toSet(), got.keys)  // "missing" (length 0) is skipped
        got.values.forEach { assertEquals("RIFF", String(it, 0, 4)) }
    }

    @Test fun fileHashMatchesServer() {
        // same algorithm as photohub.hashing.file_sha256 (whole-file SHA-256, lowercase hex)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LocalIndexer.sha256("abc".byteInputStream()))
    }
}
