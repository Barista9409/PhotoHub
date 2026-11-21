package com.photohub.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A photo/video known to the NAS (from F7 delta sync). */
@Entity(tableName = "remote_media", indices = [Index("takenAt"), Index("fileHash")])
data class RemoteMedia(
    @PrimaryKey val hash: String,
    val fileHash: String,
    val kind: String,
    val mime: String?,
    val takenAt: Long,
    val tz: Int?,
    val lat: Double?,
    val lon: Double?,
    val w: Int?,
    val h: Int?,
    val duration: Double?,
    val bytes: Long,
    val name: String,
    val stores: String,       // comma separated: nas,google,amazon
    val pair: String?,
    val description: String?,
    val rev: Long,
)

@Entity(tableName = "album")
data class AlbumRow(@PrimaryKey val id: Long, val name: String, val origin: String)

@Entity(tableName = "album_media", primaryKeys = ["albumId", "hash"], indices = [Index("hash")])
data class AlbumMedia(val albumId: Long, val hash: String)

/** A photo/video in the phone's MediaStore. fileHash links it to the NAS copy. */
@Entity(tableName = "local_media", indices = [Index("fileHash"), Index("takenAt")])
data class LocalMedia(
    @PrimaryKey val mediaStoreId: Long,
    val uri: String,
    val kind: String,
    val mime: String?,
    val name: String,
    val size: Long,
    val modified: Long,
    val takenAt: Long,
    val w: Int?,
    val h: Int?,
    val lat: Double?,
    val lon: Double?,
    val fileHash: String?,
    val bucket: String? = null,      // phone folder ("LINE", "Screenshots", "Camera", ...)
    val relPath: String? = null,     // e.g. "Pictures/LINE/"
)

data class FolderCount(val bucket: String, val count: Int, val coverUri: String?)

/** One row of the merged timeline (spec F6: phone + NAS, one entry per photo). */
data class TimelineItem(
    val itemKey: String,      // remote hash, or "local:<id>"
    val hash: String?,        // remote content hash (null = phone only)
    val localUri: String?,
    val kind: String,
    val takenAt: Long,
    val tz: Int?,
    val name: String,
    val stores: String,       // includes "phone" when on the device
    val lat: Double?,
    val lon: Double?,
    val bytes: Long,
    val w: Int?,
    val h: Int?,
    val duration: Double?,
    val mime: String?,
    val driveId: String? = null,   // copy in the person's Google Drive (reachable without VPN)
)

/** A photo this phone put in Google Drive (from the Drive folder listing). */
@Entity(tableName = "drive_file", indices = [Index("takenAt")])
data class DriveFile(
    @PrimaryKey val fileHash: String,
    val driveId: String,
    val name: String,
    val size: Long,
    val takenAt: Long,
    val mime: String?,
)

private const val TIMELINE_SELECT = """
    SELECT r.hash AS itemKey, r.hash AS hash,
           (SELECT l.uri FROM local_media l WHERE l.fileHash = r.fileHash LIMIT 1) AS localUri,
           r.kind AS kind, r.takenAt AS takenAt, r.tz AS tz, r.name AS name,
           CASE WHEN EXISTS (SELECT 1 FROM local_media l WHERE l.fileHash = r.fileHash)
                THEN r.stores || ',phone' ELSE r.stores END AS stores,
           r.lat AS lat, r.lon AS lon, r.bytes AS bytes, r.w AS w, r.h AS h, r.duration AS duration, r.mime AS mime,
           (SELECT d.driveId FROM drive_file d WHERE d.fileHash = r.fileHash) AS driveId
    FROM remote_media r
    UNION ALL
    SELECT 'local:' || l.mediaStoreId AS itemKey, NULL AS hash, l.uri AS localUri, l.kind AS kind,
           l.takenAt AS takenAt, NULL AS tz, l.name AS name, 'phone' AS stores, l.lat AS lat, l.lon AS lon,
           l.size AS bytes, l.w AS w, l.h AS h, NULL AS duration, l.mime AS mime,
           (SELECT d.driveId FROM drive_file d WHERE d.fileHash = l.fileHash) AS driveId
    FROM local_media l
    WHERE l.fileHash IS NULL OR NOT EXISTS (SELECT 1 FROM remote_media r WHERE r.fileHash = l.fileHash)
    UNION ALL
    SELECT 'drive:' || d.fileHash AS itemKey, NULL AS hash, NULL AS localUri,
           CASE WHEN d.mime LIKE 'video/%' THEN 'video' ELSE 'photo' END AS kind,
           d.takenAt AS takenAt, NULL AS tz, d.name AS name, 'drive' AS stores, NULL AS lat, NULL AS lon,
           d.size AS bytes, NULL AS w, NULL AS h, NULL AS duration, d.mime AS mime, d.driveId AS driveId
    FROM drive_file d
    WHERE NOT EXISTS (SELECT 1 FROM remote_media r WHERE r.fileHash = d.fileHash)
      AND NOT EXISTS (SELECT 1 FROM local_media l WHERE l.fileHash = d.fileHash)
"""

data class AlbumWithCount(val id: Long, val name: String, val origin: String, val count: Int, val coverHash: String?)

data class GeoPoint(val itemKey: String, val hash: String?, val localUri: String?, val lat: Double, val lon: Double, val takenAt: Long)

@Dao
interface MediaDao {
    @Query("SELECT * FROM ($TIMELINE_SELECT) ORDER BY takenAt DESC")
    fun timeline(): Flow<List<TimelineItem>>

    @Query("SELECT * FROM ($TIMELINE_SELECT) WHERE (name LIKE '%' || :q || '%' OR hash IN (SELECT am.hash FROM album_media am JOIN album a ON a.id = am.albumId WHERE a.name LIKE '%' || :q || '%') OR hash IN (SELECT hash FROM remote_media WHERE description LIKE '%' || :q || '%')) AND takenAt BETWEEN :from AND :to AND (:kind = '' OR kind = :kind) AND (:withPlace = 0 OR lat IS NOT NULL) ORDER BY takenAt DESC LIMIT 5000")
    fun search(q: String, from: Long, to: Long, kind: String, withPlace: Int): Flow<List<TimelineItem>>

    @Query("SELECT * FROM ($TIMELINE_SELECT) WHERE hash IN (SELECT hash FROM album_media WHERE albumId = :albumId) ORDER BY takenAt DESC")
    fun album(albumId: Long): Flow<List<TimelineItem>>

    @Query("SELECT itemKey, hash, localUri, lat, lon, takenAt FROM ($TIMELINE_SELECT) WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY takenAt DESC")
    fun geo(): Flow<List<GeoPoint>>

    @Query("""SELECT a.id AS id, a.name AS name, a.origin AS origin, COUNT(am.hash) AS count,
              (SELECT am2.hash FROM album_media am2 JOIN remote_media r ON r.hash = am2.hash
               WHERE am2.albumId = a.id ORDER BY r.takenAt DESC LIMIT 1) AS coverHash
              FROM album a LEFT JOIN album_media am ON am.albumId = a.id GROUP BY a.id ORDER BY a.name""")
    fun albums(): Flow<List<AlbumWithCount>>

    @Query("SELECT * FROM remote_media WHERE hash = :hash")
    suspend fun remote(hash: String): RemoteMedia?

    @Query("SELECT * FROM remote_media WHERE takenAt >= :since ORDER BY takenAt DESC")
    suspend fun remoteSince(since: Long): List<RemoteMedia>

    @Query("SELECT * FROM remote_media WHERE hash IN (SELECT hash FROM album_media WHERE albumId IN (SELECT id FROM album WHERE name IN (:names)))")
    suspend fun remoteInAlbums(names: List<String>): List<RemoteMedia>

    @Query("SELECT * FROM remote_media WHERE hash IN (:hashes)")
    suspend fun remoteByHashes(hashes: List<String>): List<RemoteMedia>

    @Query("SELECT hash FROM remote_media")
    suspend fun allRemoteHashes(): List<String>

    @Query("SELECT COUNT(*) FROM remote_media")
    fun remoteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_media")
    fun localCount(): Flow<Int>

    @Upsert
    suspend fun upsertRemote(items: List<RemoteMedia>)

    @Query("DELETE FROM remote_media WHERE hash IN (:hashes)")
    suspend fun deleteRemote(hashes: List<String>)

    @Query("DELETE FROM album_media WHERE hash IN (:hashes)")
    suspend fun clearAlbumLinks(hashes: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumLinks(links: List<AlbumMedia>)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumRow>)

    @Query("DELETE FROM album WHERE id NOT IN (:keep)")
    suspend fun pruneAlbums(keep: List<Long>)

    @Transaction
    suspend fun applySync(upserts: List<RemoteMedia>, deletes: List<String>, links: List<AlbumMedia>, albums: List<AlbumRow>) {
        if (albums.isNotEmpty()) {
            upsertAlbums(albums)
            pruneAlbums(albums.map { it.id })
        }
        val touched = upserts.map { it.hash } + deletes
        touched.chunked(500).forEach { clearAlbumLinks(it) }
        deletes.chunked(500).forEach { deleteRemote(it) }
        upsertRemote(upserts)
        insertAlbumLinks(links)
    }

    // --- phone folders (shown as "このスマホのフォルダ" and sent to the NAS as albums) ---
    @Query("""SELECT bucket, COUNT(*) AS count, (SELECT l2.uri FROM local_media l2 WHERE l2.bucket = l.bucket
              ORDER BY l2.takenAt DESC LIMIT 1) AS coverUri FROM local_media l WHERE bucket IS NOT NULL
              GROUP BY bucket ORDER BY count DESC""")
    fun folders(): Flow<List<FolderCount>>

    @Query("""SELECT * FROM ($TIMELINE_SELECT) WHERE localUri IN (SELECT uri FROM local_media WHERE bucket = :bucket)
              ORDER BY takenAt DESC""")
    fun folder(bucket: String): Flow<List<TimelineItem>>

    @Query("SELECT DISTINCT bucket FROM local_media WHERE bucket IS NOT NULL")
    suspend fun bucketNames(): List<String>

    @Query("SELECT fileHash FROM local_media WHERE bucket = :bucket AND fileHash IS NOT NULL")
    suspend fun hashesIn(bucket: String): List<String>

    @Query("SELECT relPath FROM local_media WHERE bucket = :bucket LIMIT 1")
    suspend fun pathOf(bucket: String): String?

    // --- local index ---
    @Query("SELECT * FROM local_media")
    suspend fun allLocal(): List<LocalMedia>

    @Query("SELECT * FROM local_media WHERE fileHash IS NULL ORDER BY takenAt DESC LIMIT :limit")
    suspend fun localWithoutHash(limit: Int): List<LocalMedia>

    @Upsert
    suspend fun upsertLocal(items: List<LocalMedia>)

    @Query("DELETE FROM local_media WHERE mediaStoreId IN (:ids)")
    suspend fun deleteLocal(ids: List<Long>)

    @Query("UPDATE local_media SET fileHash = :hash WHERE mediaStoreId = :id")
    suspend fun setLocalHash(id: Long, hash: String)

    @Query("DELETE FROM remote_media")
    suspend fun clearRemote()

    // --- backup ---
    @Query("SELECT * FROM local_media WHERE fileHash IS NOT NULL ORDER BY takenAt DESC")
    suspend fun localHashed(): List<LocalMedia>

    @Query("SELECT fileHash FROM remote_media")
    suspend fun remoteFileHashes(): List<String>

    @Query("SELECT * FROM drive_file")
    suspend fun driveFiles(): List<DriveFile>

    @Upsert
    suspend fun upsertDrive(items: List<DriveFile>)

    @Query("DELETE FROM drive_file WHERE fileHash IN (:hashes)")
    suspend fun deleteDrive(hashes: List<String>)

    @Query("DELETE FROM drive_file")
    suspend fun clearDrive()

    /** Phone photos that are safely on the NAS (candidates for freeing up space). */
    @Query("""SELECT l.* FROM local_media l WHERE l.fileHash IN (SELECT fileHash FROM remote_media)
              AND l.takenAt < :before ORDER BY l.takenAt""")
    suspend fun localOnNas(before: Long): List<LocalMedia>

    @Query("DELETE FROM album")
    suspend fun clearAlbumRows()

    @Query("DELETE FROM album_media")
    suspend fun clearAlbumMedia()

    @Transaction
    suspend fun clearAlbums() { clearAlbumMedia(); clearAlbumRows() }
}

@Database(entities = [RemoteMedia::class, AlbumRow::class, AlbumMedia::class, LocalMedia::class, DriveFile::class],
    version = 3, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "photohub.db").fallbackToDestructiveMigration(true).build()
    }
}
