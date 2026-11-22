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
    val space: String = "mine",
    val folder: String = "",
    val place: String? = null,
)

@Serializable
data class SyncAlbum(val id: Long, val name: String, val origin: String, val shared: Boolean = false)

@Serializable
data class SyncPage(val items: List<SyncItem>, val albums: List<SyncAlbum>, val cursor: Long, @SerialName("has_more") val hasMore: Boolean)

@Serializable
data class Me(val id: Long, val name: String, val admin: Boolean = false, @SerialName("takeout_target") val takeoutTarget: String = "personal",
              @SerialName("photo_folder") val photoFolder: Boolean = true,
              @SerialName("photos_linked") val photosLinked: Boolean = false)

@Serializable
data class ProbeResult(val ok: Boolean = false, val report: String = "")

@Serializable
data class MySettings(
    @SerialName("takeout_target") val takeoutTarget: String? = null,
    @SerialName("backup_dir") val backupDir: String? = null,
    @SerialName("drive_root") val driveRoot: String? = null,
    @SerialName("drive_photos_dir") val drivePhotosDir: String? = null,
    @SerialName("takeout_dir") val takeoutDir: String? = null,
)

@Serializable
data class AlbumNewBody(val name: String, val hashes: List<String> = emptyList(), val shared: Boolean = false)

@Serializable
data class AlbumNameBody(val name: String)

@Serializable
data class MoveBody(val hashes: List<String>, val to: String)

@Serializable
data class PathsBody(val paths: List<String>)

@Serializable
data class TrashItem(val hash: String, val name: String = "", val kind: String = "photo", @SerialName("taken_at") val takenAt: Long? = null,
                     val by: String? = null, @SerialName("days_left") val daysLeft: Int = 30, val space: String = "mine")

@Serializable
data class TrashList(val days: Int = 30, val items: List<TrashItem> = emptyList())

@Serializable
data class DuplicateFile(val path: String, val hash: String, val keep: String = "", val bytes: Long = 0)

@Serializable
data class DuplicateList(val items: List<DuplicateFile> = emptyList())

@Serializable
data class HashScore(val hash: String, val sharpness: Double = 0.0)

@Serializable
data class HashScores(val items: List<HashScore> = emptyList())

@Serializable
data class ZipInfo(val name: String = "", val bytes: Long = 0)

@Serializable
data class TakeoutProgress(
    val archive: String = "", val parts: Int = 0, val step: String = "", val done: Int = 0, val total: Int = 0,
    val new: Int = 0, val duplicate: Int = 0, val error: Int = 0, @SerialName("dry_run") val dryRun: Boolean = false,
    val finished: Boolean = false, val updated: Double = 0.0,
)

@Serializable
data class TakeoutJob(val id: Long = 0, val state: String = "", val archive: String? = null, val new: Int = 0,
                      val duplicate: Int = 0, val error: Int = 0, @SerialName("finished_at") val finishedAt: String? = null)

@Serializable
data class TakeoutStatus(val apply: Boolean = false, val sources: List<ZipInfo> = emptyList(), val waiting: List<ZipInfo> = emptyList(),
                         val current: TakeoutProgress? = null, val jobs: List<TakeoutJob> = emptyList(),
                         val deleted: List<DeletedZip> = emptyList(), val leftover: List<ZipInfo> = emptyList())

@Serializable
data class DeletedZip(val name: String = "", val at: String = "")

@Serializable
data class BrowseResult(val path: String = "", val folders: List<String> = emptyList(), val zips: Int = 0)

@Serializable
data class FolderEntry(val path: String, val depth: Int = 1)

@Serializable
data class MyFolders(
    val root: String = "", val folders: List<FolderEntry> = emptyList(), @SerialName("backup_dir") val backupDir: String = "",
    val home: String = "", @SerialName("home_folders") val homeFolders: List<FolderEntry> = emptyList(),
    @SerialName("drive_root") val driveRoot: String = "", @SerialName("drive_detected") val driveDetected: String = "",
    @SerialName("drive_photos_dir") val drivePhotosDir: String = "", @SerialName("takeout_dir") val takeoutDir: String = "",
    @SerialName("takeout_detected") val takeoutDetected: String = "",
)

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
    @SerialName("scan_progress") val scanProgress: String? = null,
    val build: String? = null,
    val remote: String? = null,   // the away-from-home address the Synology knows (PHOTOHUB_REMOTE_URL / Tailscale)
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
data class DeviceAlbumBody(val name: String, @SerialName("file_hashes") val fileHashes: List<String>)

@Serializable
data class DeviceAlbumResult(@SerialName("album_id") val albumId: Long = 0, val matched: Int = 0, val added: Int = 0)

@Serializable
data class HashesBody(val hashes: List<String>)

interface PhotoHubApi {
    @GET("api/health") suspend fun health(): Map<String, kotlinx.serialization.json.JsonElement>
    @GET("api/me") suspend fun me(): Me
    @POST("api/have") suspend fun have(@Body body: HashesBody): Map<String, String>
    @POST("api/device-albums") suspend fun deviceAlbum(@Body body: DeviceAlbumBody): DeviceAlbumResult
    @retrofit2.http.PUT("api/me/settings") suspend fun setMySettings(@Body body: MySettings): MySettings
    @GET("api/status") suspend fun status(): Status
    @GET("api/me/folders") suspend fun myFolders(): MyFolders
    @GET("api/me/takeout") suspend fun takeoutStatus(): TakeoutStatus
    @POST("api/me/takeout/cleanup") suspend fun takeoutCleanup(): Map<String, Int>
    @POST("api/media/delete") suspend fun deleteMedia(@Body body: HashesBody): Map<String, Int>
    @POST("api/albums") suspend fun createAlbum(@Body body: AlbumNewBody): Map<String, Long>
    @retrofit2.http.PUT("api/albums/{id}") suspend fun renameAlbum(@retrofit2.http.Path("id") id: Long, @Body body: AlbumNameBody): Map<String, Boolean>
    @retrofit2.http.DELETE("api/albums/{id}") suspend fun deleteAlbum(@retrofit2.http.Path("id") id: Long): Map<String, Boolean>
    @POST("api/albums/{id}/add") suspend fun addToAlbum(@retrofit2.http.Path("id") id: Long, @Body body: HashesBody): Map<String, Int>
    @POST("api/albums/{id}/remove") suspend fun removeFromAlbum(@retrofit2.http.Path("id") id: Long, @Body body: HashesBody): Map<String, Int>
    @POST("api/albums/pull") suspend fun pullAlbums(): Map<String, kotlinx.serialization.json.JsonElement>
    @POST("api/media/move") suspend fun moveMedia(@Body body: MoveBody): Map<String, Int>
    @GET("api/trash") suspend fun trash(): TrashList
    @POST("api/trash/restore") suspend fun restore(@Body body: HashesBody): Map<String, Int>
    @POST("api/trash/empty") suspend fun emptyTrash(@Body body: HashesBody): Map<String, Int>
    @GET("api/duplicates") suspend fun duplicates(): DuplicateList
    @POST("api/duplicates/remove") suspend fun removeDuplicates(@Body body: PathsBody): Map<String, Int>
    @GET("api/suggestions/blurry") suspend fun blurry(): HashScores
    @GET("api/me/browse") suspend fun browse(@retrofit2.http.Query("base") base: String, @retrofit2.http.Query("path") path: String): BrowseResult
    @POST("api/me/synology-probe") suspend fun synologyProbe(): ProbeResult
    @retrofit2.http.DELETE("api/me/synology-link") suspend fun synologyUnlink(): Map<String, Boolean>
    @POST("api/drive-removed") suspend fun driveRemoved(@Body body: HashesBody): Map<String, Int>
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
