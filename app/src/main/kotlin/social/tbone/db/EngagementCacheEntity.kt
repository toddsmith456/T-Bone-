package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Compact per-account engagement summary persisted for recent feed notes so
 * cold starts display real like/reply counts immediately instead of flashing 0
 * until relay EOSE returns.
 */
@Entity(
    tableName = "engagement_cache",
    primaryKeys = ["eventId", "accountPubkey"],
    indices = [Index(value = ["updatedAt"])],
)
data class EngagementCacheEntity(
    val eventId: String,
    val accountPubkey: String,
    val likeCount: Int,
    val replyCount: Int,
    @ColumnInfo(defaultValue = "0") val likedByMe: Boolean = false,
    @ColumnInfo(defaultValue = "0") val repliedByMe: Boolean = false,
    val myReactionContent: String? = null,
    val updatedAt: Long,
)

@Dao
interface EngagementCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<EngagementCacheEntity>)

    @Query("SELECT * FROM engagement_cache WHERE accountPubkey = :accountPubkey AND eventId IN (:eventIds)")
    suspend fun getForEvents(accountPubkey: String, eventIds: List<String>): List<EngagementCacheEntity>

    @Query(
        "DELETE FROM engagement_cache WHERE rowid NOT IN (" +
            "SELECT rowid FROM engagement_cache ORDER BY updatedAt DESC LIMIT :maxRows" +
            ")",
    )
    suspend fun trimToLimit(maxRows: Int): Int

    @Query("DELETE FROM engagement_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM engagement_cache")
    suspend fun count(): Int
}
