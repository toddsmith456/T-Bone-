package social.tbone.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Upsert
    suspend fun upsert(event: EventEntity)

    @Query("SELECT * FROM events WHERE kind IN (:kinds) AND accountPubkey = :accountPubkey ORDER BY createdAt DESC LIMIT :limit")
    fun observeByKinds(kinds: List<Int>, accountPubkey: String, limit: Int = 500): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind IN (:kinds) AND accountPubkey = :accountPubkey ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByKinds(kinds: List<Int>, accountPubkey: String, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EventEntity>
}
