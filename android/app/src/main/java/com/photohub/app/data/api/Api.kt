package com.photohub.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.DataInputStream
import java.io.InputStream

@Serializable
data class SyncItem(
    val hash: String,
    @SerialName("file_hash") val fileHash: String,
    val kind: String,
    val mime: String? = null,
    @SerialName("taken_at") val takenAt: Long? = null,
    val tz: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val w: Int? = null,
    val h: Int? = null,
    val duration: Double? = null,
    val bytes: Long = 0,
    val name: String,
    val albums: List<Long> = emptyList(),
    val stores: List<String> = emptyList(),
    val deleted: Boolean = false,
    val rev: Long,
    val pair: String? = null,
    val desc: String? = null,
)

@Serializable
data class SyncAlbum(val id: Long, val name: String, val origin: String)

@Serializable
data class SyncPage(val items: List<SyncItem>, val albums: List<SyncAlbum>, val cursor: Long, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class Me(val id: Long, val name: String, val admin: Boolean = false, @SerialName("takeout_target") val takeoutTarget: String = "personal")

@Serializable
data class MySettings(@SerialName("takeout_target") val takeoutTarget: String)

@Serializable
data class Status(
    val user: String? = null,
    val admin: Boolean = false,
    val photos: Int = 0,
    val videos: Int = 0,
    val stores: Map<String, Int> = emptyMap(),
    @SerialName("last_import_at") val lastImportAt: String? = null,
    @SerialName("last_scan_at") val lastScanAt: String? = null,
    @SerialName("last_device_sync_at") val lastDeviceSyncAt: String? = null,
    val duplicates: Int = 0,
    @SerialName("similar_open") val similarOpen: Int = 0,
    val apply: Boolean = false,
    @SerialName("amazon_heartbeat") val amazonHeartbeat: String? = null,
)

@Serializable
data class Job(
    val id: Long,
    val source: String,
    val user: String? = null,
    val archive: String,
    val state: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String? = null,
    val new: Int = 0,
    val duplicate: Int = 0,
    val error: Int = 0,
    val message: String? = null,
)

@Serializable
data class JobError(val path: String, val message: String? = null)

@Serializable
data class SimilarMember(
    @SerialName("media_id") val mediaId: Long,
    val hash: String,
    val w: Int? = null,
    val h: Int? = null,
    val bytes: Long = 0,
    @SerialName("taken_at") val takenAt: Long? = null,
    val name: String,
    val keep: Boolean? = null,
)

@Serializable
data class SimilarGroup(val id: Long, val status: String, val space: String = "shared", val members: List<SimilarMember>)

@Serializable
data class KeepBody(val keep: List<Long>)

@Serializable
data class ResolveResult(val applied: Boolean, val actions: List<String>)

@Serializable
data class Candidate(
    val id: Long,
    val month: String,
    val match: String,
    val photos: Int,
    val videos: Int,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    val note: String = "",
)

@Serializable
data class ConfirmResult(@SerialName("marked_removed") val markedRemoved: Int)

/** photohub-<ver>.json attached to each GitHub Release (written by ./gradlew publishUpdate). */
@Serializable
data class AppRelease(
    val versionCode: Long = 0,
    val versionName: String = "",
    val notes: String = "",
    val apk: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val apkUrl: String = "",   // filled in from the release's asset list
)

@Serializable
data class GhAsset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

@Serializable
data class GhRelease(@SerialName("tag_name") val tag: String, val draft: Boolean = false,
                     val prerelease: Boolean = false, val assets: List<GhAsset> = emptyList())

@Serializable
data class HashesBody(val hashes: List<String>)

interface PhotoHubApi {
    @GET("api/health") suspend fun health(): Map<String, kotlinx.serialization.json.JsonElement>
    @GET("api/me") suspend fun me(): Me
    @retrofit2.http.PUT("api/me/settings") suspend fun setMySettings(@Body body: MySettings): MySettings
    @GET("api/status") suspend fun status(): Status
    @GET("api/jobs") suspend fun jobs(@Query("limit") limit: Int = 50): List<Job>
    @GET("api/jobs/{id}/errors") suspend fun jobErrors(@Path("id") id: Long): List<JobError>
    @GET("api/sync") suspend fun sync(@Query("cursor") cursor: Long, @Query("limit") limit: Int = 2000): SyncPage
    @POST("api/thumbs") suspend fun thumbs(@Body body: HashesBody): ResponseBody
    @GET("api/similar") suspend fun similar(@Query("status") status: String = "open"): List<SimilarGroup>
    @POST("api/similar/{id}/resolve") suspend fun resolve(@Path("id") id: Long, @Body body: KeepBody): ResolveResult
    @GET("api/google-candidates") suspend fun candidates(): List<Candidate>
    @POST("api/google-candidates/{id}/confirm") suspend fun confirm(@Path("id") id: Long, @Body empty: RequestBody): ConfirmResult
    @POST("api/device-sync") suspend fun deviceSync(@Body empty: RequestBody): Map<String, Boolean>
}

/** Reader for the thumbnail pack: "PHP1" u32 count, then (u16 len, hash, u32 len, webp)*. */
object ThumbPack {
    fun read(input: InputStream, onItem: (hash: String, data: ByteArray) -> Unit) {
        val din = DataInputStream(input.buffered())
        val magic = ByteArray(4).also { din.readFully(it) }
        require(String(magic) == "PHP1") { "bad thumbnail pack" }
        val count = din.readInt()
        repeat(count) {
            val hl = din.readUnsignedShort()
            val hash = String(ByteArray(hl).also { din.readFully(it) })
            val len = din.readInt()
            val data = ByteArray(len).also { din.readFully(it) }
            if (len > 0) onItem(hash, data)
        }
    }
}
