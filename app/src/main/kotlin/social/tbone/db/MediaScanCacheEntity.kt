package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * On-device cache of offline AI NSFW classification scores.
 *
 * Keyed by an Android Keystore HMAC-SHA256 digest of the media identifier
 * (NIP-92 imeta SHA-256 when present, else canonical URL) so the SQLite file
 * never stores plaintext URLs of images the user scrolled past.
 *
 * Stores raw floating-point scores rather than a baked allow/block decision so
 * changing sensitivity or action mode takes effect immediately without re-scanning.
 */
@Entity(
    tableName = "media_scan_cache",
    indices = [Index(value = ["scannedAt"])],
)
data class MediaScanCacheEntity(
    @PrimaryKey val keyHmac: String,
    val explicitScore: Float,
    val suggestiveScore: Float,
    val stageReached: Int,
    val modelVersion: Int,
    val scannedAt: Long,
    @ColumnInfo(defaultValue = "0")
    val userOverrideAllow: Boolean = false,
)

@Dao
interface MediaScanCacheDao {
    @Query("SELECT * FROM media_scan_cache WHERE keyHmac = :keyHmac LIMIT 1")
    suspend fun get(keyHmac: String): MediaScanCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MediaScanCacheEntity)

    @Query("UPDATE media_scan_cache SET userOverrideAllow = :allow WHERE keyHmac = :keyHmac")
    suspend fun setUserOverride(keyHmac: String, allow: Boolean)

    @Query("DELETE FROM media_scan_cache WHERE scannedAt < :cutoffMs OR modelVersion != :currentModelVersion")
    suspend fun pruneExpiredOrStale(cutoffMs: Long, currentModelVersion: Int)

    @Query(
        "DELETE FROM media_scan_cache WHERE keyHmac IN (" +
            "SELECT keyHmac FROM media_scan_cache ORDER BY scannedAt ASC " +
            "LIMIT MAX(0, (SELECT COUNT(*) FROM media_scan_cache) - :maxRows)" +
            ")",
    )
    suspend fun trimToLimit(maxRows: Int)

    @Query("DELETE FROM media_scan_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM media_scan_cache")
    suspend fun count(): Int
}
