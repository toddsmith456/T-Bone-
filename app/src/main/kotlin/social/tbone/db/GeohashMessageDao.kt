package social.tbone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GeohashMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(msg: GeohashMessageEntity)

    @Query("SELECT * FROM geohash_messages WHERE channel_code = :code AND cached_at >= :cutoff ORDER BY created_at ASC")
    suspend fun getForChannel(code: String, cutoff: Long): List<GeohashMessageEntity>

    @Query("DELETE FROM geohash_messages")
    suspend fun deleteAll()

    @Query("DELETE FROM geohash_messages WHERE cached_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
