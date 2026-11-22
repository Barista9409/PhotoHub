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
    val space: String = "mine",   // mine / shared (family) / granted
    val folder: String = "",      // folder inside its space, e.g. "2024年 沖縄旅行"
    val place: String? = null,    // e.g. "東京都 渋谷区" (from the GPS position, named on the Synology)
    @androidx.room.ColumnInfo(defaultValue = "0") val rot: Int = 0,   // clockwise turn to show it upright
)

data class DateToFix(val fileHash: String, val takenAt: Long)

data class PlaceCount(val place: String, val n: Int, val hash: String)

/** A photo's folder in the family's shared space (for browsing 共有 by folder). */
data class FolderOf(val hash: String, val folder: String, val takenAt: Long)

@Entity(tableName = "album")
data class AlbumRow(@PrimaryKey val id: Long, val name: String, val origin: String, val shared: Boolean = false)

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
    @androidx.room.ColumnInfo(defaultValue = "0")
    val onNas: Boolean = false,      // the NAS said it has this file (before the NAS list catches up)
)

/** One line of the library breakdown: where, photo/video, how many, how big. */
data class KindCount(val kind: String, val n: Int, val bytes: Long)

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
    val place: String? = null,
    val rot: Int = 0,
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

private const val REMOTE_PART = """
    SELECT r.hash AS itemKey, r.hash AS hash,
           (SELECT l.uri FROM local_media l WHERE l.fileHash = r.fileHash LIMIT 1) AS localUri,
           r.kind AS kind,
           -- a photo dated the day it reached the Synology (no date inside the file): the phone's date is the real one
           COALESCE((SELECT MIN(l.takenAt) FROM local_media l WHERE l.fileHash = r.fileHash AND l.takenAt < r.takenAt - 86400000),
                    (SELECT MIN(d.takenAt) FROM drive_file d WHERE d.fileHash = r.fileHash AND d.takenAt > 0 AND d.takenAt < r.takenAt - 86400000),
                    r.takenAt) AS takenAt,
           r.tz AS tz, r.name AS name,
           r.stores
             || CASE WHEN EXISTS (SELECT 1 FROM local_media l WHERE l.fileHash = r.fileHash) THEN ',phone' ELSE '' END
             || CASE WHEN EXISTS (SELECT 1 FROM drive_file d WHERE d.fileHash = r.fileHash) AND r.stores NOT LIKE '%drive%' THEN ',drive' ELSE '' END
             AS stores,
           r.lat AS lat, r.lon AS lon, r.bytes AS bytes, r.w AS w, r.h AS h, r.duration AS duration, r.mime AS mime,
           (SELECT d.driveId FROM drive_file d WHERE d.fileHash = r.fileHash) AS driveId, r.place AS place, r.rot AS rot
    FROM remote_media r
"""

private const val TIMELINE_SELECT = REMOTE_PART + """
    UNION ALL
    SELECT 'local:' || l.mediaStoreId AS itemKey, NULL AS hash, l.uri AS localUri, l.kind AS kind,
           l.takenAt AS takenAt, NULL AS tz, l.name AS name,
           'phone' || CASE WHEN l.onNas THEN ',nas' ELSE '' END
                   || CASE WHEN EXISTS (SELECT 1 FROM drive_file d WHERE d.fileHash = l.fileHash) THEN ',drive' ELSE '' END AS stores,
           l.lat AS lat, l.lon AS lon,
           l.size AS bytes, l.w AS w, l.h AS h, NULL AS duration, l.mime AS mime,
           (SELECT d.driveId FROM drive_file d WHERE d.fileHash = l.fileHash) AS driveId, NULL AS place, 0 AS rot
    FROM local_media l
    WHERE l.fileHash IS NULL OR NOT EXISTS (SELECT 1 FROM remote_media r WHERE r.fileHash = l.fileHash)
    UNION ALL
    SELECT 'drive:' || d.fileHash AS itemKey, NULL AS hash, NULL AS localUri,
           CASE WHEN d.mime LIKE 'video/%' THEN 'video' ELSE 'photo' END AS kind,
           d.takenAt AS takenAt, NULL AS tz, d.name AS name, 'drive' AS stores, NULL AS lat, NULL AS lon,
           d.size AS bytes, NULL AS w, NULL AS h, NULL AS duration, d.mime AS mime, d.driveId AS driveId, NULL AS place, 0 AS rot
    FROM drive_file d
    WHERE NOT EXISTS (SELECT 1 FROM remote_media r WHERE r.fileHash = d.fileHash)
      AND NOT EXISTS (SELECT 1 FROM local_media l WHERE l.fileHash = d.fileHash)
"""

data class AlbumWithCount(val id: Long, val name: String, val origin: String, val count: Int, val coverHash: String?,
                          val shared: Boolean = false)

data class GeoPoint(val itemKey: String, val hash: String?, val localUri: String?, val lat: Double, val lon: Double, val takenAt: Long)

@Dao
interface MediaDao {
    @Query("SELECT * FROM ($TIMELINE_SELECT) ORDER BY takenAt DESC")
    fun timeline(): Flow<List<TimelineItem>>

    @Query("SELECT * FROM ($TIMELINE_SELECT) WHERE (name LIKE '%' || :q || '%' OR place LIKE '%' || :q || '%' OR hash IN (SELECT am.hash FROM album_media am JOIN album a ON a.id = am.albumId WHERE a.name LIKE '%' || :q || '%') OR hash IN (SELECT hash FROM remote_media WHERE description LIKE '%' || :q || '%')) AND takenAt BETWEEN :from AND :to AND (:kind = '' OR kind = :kind) AND (:withPlace = 0 OR lat IS NOT NULL) ORDER BY takenAt DESC LIMIT 5000")
    fun search(q: String, from: Long, to: Long, kind: String, withPlace: Int): Flow<List<TimelineItem>>

    @Query("SELECT * FROM ($TIMELINE_SELECT) WHERE hash IN (SELECT hash FROM album_media WHERE albumId = :albumId) ORDER BY takenAt DESC")
    fun album(albumId: Long): Flow<List<TimelineItem>>

    @Query("SELECT itemKey, hash, localUri, lat, lon, takenAt FROM ($TIMELINE_SELECT) WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY takenAt DESC")
    fun geo(): Flow<List<GeoPoint>>

    /** Only the shared space: straight from remote_media (no union over the whole library). */
    @Query("$REMOTE_PART WHERE r.space = 'shared' ORDER BY r.takenAt DESC")
    fun sharedSpace(): Flow<List<TimelineItem>>

    @Query("""SELECT place, COUNT(*) AS n, MAX(hash) AS hash FROM remote_media WHERE place IS NOT NULL AND place != ''
              GROUP BY place ORDER BY n DESC LIMIT 40""")
    fun places(): Flow<List<PlaceCount>>

    @Query("SELECT hash, folder, takenAt FROM remote_media WHERE space = 'shared'")
    fun sharedFolders(): Flow<List<FolderOf>>

    @Query("""SELECT a.id AS id, a.name AS name, a.origin AS origin, COUNT(am.hash) AS count, a.shared AS shared,
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

    @Query("SELECT hash FROM remote_media ORDER BY takenAt DESC")  // newest first: what people look at
    suspend fun allRemoteHashes(): List<String>

    @Query("SELECT COUNT(*) FROM remote_media")
    fun remoteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM local_media")
    fun localCount(): Flow<Int>

    @Upsert
    suspend fun upsertRemote(items: List<RemoteMedia>)

    @Query("UPDATE remote_media SET rot = :rot WHERE hash = :hash")
    suspend fun setRot(hash: String, rot: Int)

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

    @Query("UPDATE local_media SET fileHash = :hash, lat = :lat, lon = :lon WHERE mediaStoreId = :id")
    suspend fun setLocalHashAndPlace(id: Long, hash: String, lat: Double?, lon: Double?)

    @Query("SELECT COUNT(*) FROM local_media WHERE fileHash IS NULL")
    suspend fun countUnhashed(): Int

    /** Synology copies dated later than the phone's own date by more than a day (see TIMELINE_SELECT). */
    @Query("""SELECT fileHash, MIN(takenAt) AS takenAt FROM (
                SELECT r.fileHash AS fileHash, l.takenAt AS takenAt FROM remote_media r JOIN local_media l ON l.fileHash = r.fileHash
                WHERE l.takenAt > 0 AND l.takenAt < r.takenAt - 86400000
                UNION ALL
                -- the phone's date kept with each Google Drive copy (still there after the phone copy is gone)
                SELECT r.fileHash, d.takenAt FROM remote_media r JOIN drive_file d ON d.fileHash = r.fileHash
                WHERE d.takenAt > 0 AND d.takenAt < r.takenAt - 86400000)
              GROUP BY fileHash LIMIT 2000""")
    suspend fun datesToFix(): List<DateToFix>

    @Query("UPDATE local_media SET onNas = 1 WHERE fileHash IN (:hashes)")
    suspend fun markOnNas(hashes: List<String>)

    // --- library breakdown ---
    @Query("SELECT kind, COUNT(*) AS n, COALESCE(SUM(size), 0) AS bytes FROM local_media GROUP BY kind")
    fun phoneCounts(): Flow<List<KindCount>>

    @Query("""SELECT kind, COUNT(*) AS n, COALESCE(SUM(bytes), 0) AS bytes FROM (
                SELECT fileHash, kind, bytes FROM remote_media
                UNION SELECT fileHash, kind, size AS bytes FROM local_media
                      WHERE onNas AND fileHash NOT IN (SELECT fileHash FROM remote_media)) GROUP BY kind""")
    fun nasCounts(): Flow<List<KindCount>>

    @Query("""SELECT CASE WHEN mime LIKE 'video/%' THEN 'video' ELSE 'photo' END AS kind, COUNT(*) AS n,
              COALESCE(SUM(size), 0) AS bytes FROM drive_file GROUP BY 1""")
    fun driveCounts(): Flow<List<KindCount>>

    @Query("""SELECT kind, COUNT(*) AS n, COALESCE(SUM(bytes), 0) AS bytes FROM remote_media
              WHERE ',' || stores || ',' LIKE '%,google,%' GROUP BY kind""")
    fun googleCounts(): Flow<List<KindCount>>

    /** On the phone and nowhere else yet. */
    @Query("""SELECT kind, COUNT(*) AS n, COALESCE(SUM(size), 0) AS bytes FROM local_media l
              WHERE NOT l.onNas AND (l.fileHash IS NULL OR (l.fileHash NOT IN (SELECT fileHash FROM remote_media)
                    AND l.fileHash NOT IN (SELECT fileHash FROM drive_file))) GROUP BY kind""")
    fun phoneOnlyCounts(): Flow<List<KindCount>>

    @Query("SELECT COUNT(*) FROM local_media WHERE fileHash IS NULL")
    fun uncheckedCount(): Flow<Int>

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
    @Query("""SELECT l.* FROM local_media l WHERE (l.onNas OR l.fileHash IN (SELECT fileHash FROM remote_media))
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
    version = 9, exportSchema = true)
abstract class AppDb : RoomDatabase() {
    abstract fun media(): MediaDao

    companion object {
        fun build(ctx: Context): AppDb =
            Room.databaseBuilder(ctx, AppDb::class.java, "photohub.db")
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)   // keep the phone's checked photos (hashing takes a while)
                .fallbackToDestructiveMigration(true).build()

        /** Photos and albums now say whether they are in the family's shared space: fetch them again
         *  (PhotoHubApp restarts the Synology list from the beginning when it finds it empty). */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS remote_media")
                db.execSQL(REMOTE_SQL)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_media_takenAt ON remote_media (takenAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_media_fileHash ON remote_media (fileHash)")
                db.execSQL("DROP TABLE IF EXISTS album")
                db.execSQL(ALBUM_SQL)
                db.execSQL("DELETE FROM album_media")
            }
        }
        /** Photos now carry their folder: fetch the Synology list again (same way as 5 -> 6). */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS remote_media")
                db.execSQL(REMOTE_SQL_7)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_media_takenAt ON remote_media (takenAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_remote_media_fileHash ON remote_media (fileHash)")
                db.execSQL("DELETE FROM album_media")
            }
        }
        /** Place names arrive with the next sync (the Synology touches every named photo). */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remote_media ADD COLUMN place TEXT")
            }
        }
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE remote_media ADD COLUMN rot INTEGER NOT NULL DEFAULT 0")
            }
        }
        private const val REMOTE_SQL_7 = "CREATE TABLE IF NOT EXISTS `remote_media` (`hash` TEXT NOT NULL, `fileHash` TEXT NOT NULL, `kind` TEXT NOT NULL, `mime` TEXT, `takenAt` INTEGER NOT NULL, `tz` INTEGER, `lat` REAL, `lon` REAL, `w` INTEGER, `h` INTEGER, `duration` REAL, `bytes` INTEGER NOT NULL, `name` TEXT NOT NULL, `stores` TEXT NOT NULL, `pair` TEXT, `description` TEXT, `rev` INTEGER NOT NULL, `space` TEXT NOT NULL, `folder` TEXT NOT NULL, PRIMARY KEY(`hash`))"
        private const val REMOTE_SQL = "CREATE TABLE IF NOT EXISTS `remote_media` (`hash` TEXT NOT NULL, `fileHash` TEXT NOT NULL, `kind` TEXT NOT NULL, `mime` TEXT, `takenAt` INTEGER NOT NULL, `tz` INTEGER, `lat` REAL, `lon` REAL, `w` INTEGER, `h` INTEGER, `duration` REAL, `bytes` INTEGER NOT NULL, `name` TEXT NOT NULL, `stores` TEXT NOT NULL, `pair` TEXT, `description` TEXT, `rev` INTEGER NOT NULL, `space` TEXT NOT NULL, PRIMARY KEY(`hash`))"
        private const val ALBUM_SQL = "CREATE TABLE IF NOT EXISTS `album` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `origin` TEXT NOT NULL, `shared` INTEGER NOT NULL, PRIMARY KEY(`id`))"

        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_media ADD COLUMN onNas INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
