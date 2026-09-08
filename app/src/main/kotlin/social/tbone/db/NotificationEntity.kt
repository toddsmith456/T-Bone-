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
 * The app caches the last 24 hours of notifications here so the tab opens
 * instantly; older history is fetched from relays on demand ("load more").
 * [json] holds the serialized FlatNotifItem (including any embedded note).
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
        "SELECT * FROM notifications WHERE pubkey = :pubkey AND createdAt >= :since " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun getRecent(pubkey: String, since: Long, limit: Int): List<NotificationEntity>

    @Query(
        "SELECT * FROM notifications WHERE pubkey = :pubkey AND createdAt < :before " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun getOlder(pubkey: String, before: Long, limit: Int): List<NotificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NotificationEntity)

    @Query("DELETE FROM notifications WHERE pubkey = :pubkey AND createdAt < :cutoff")
    suspend fun deleteOlderThan(pubkey: String, cutoff: Long)

    @Query("DELETE FROM notifications WHERE pubkey = :pubkey")
    suspend fun clear(pubkey: String)
}
