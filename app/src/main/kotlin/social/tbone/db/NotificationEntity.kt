package social.tbone.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Persisted notification row for offline caching.
 *
 * The app retains the latest 200 notification rows per account so the tab
 * opens instantly even when some are older than 24 hours. Older history is
 * fetched from relays on demand ("load more"). [json] holds the serialized
 * FlatNotifItem, including any embedded note and its media URLs.
 */
@Entity(
    tableName = "notifications",
    indices = [Index(value = ["pubkey", "createdAt"])],
)
data class NotificationEntity(
    @PrimaryKey val id: String,
    val pubkey: String,
    val type: String,
    val json: String,
    val createdAt: Long,
)

@Dao
interface NotificationDao {

    @Query(
        "SELECT * FROM notifications WHERE pubkey = :pubkey " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun getLatest(pubkey: String, limit: Int): List<NotificationEntity>

    @Query(
        "SELECT * FROM notifications WHERE pubkey = :pubkey AND createdAt < :before " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun getOlder(pubkey: String, before: Long, limit: Int): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NotificationEntity)

    @Query(
        "DELETE FROM notifications WHERE pubkey = :pubkey AND id NOT IN (" +
            "SELECT id FROM notifications WHERE pubkey = :pubkey " +
            "ORDER BY createdAt DESC, id DESC LIMIT :keep)"
    )
    suspend fun keepLatest(pubkey: String, keep: Int)

    @Query("DELETE FROM notifications WHERE pubkey = :pubkey")
    suspend fun clear(pubkey: String)
}
