package social.tbone.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {

    @Upsert
    suspend fun upsert(event: EventEntity)

    @Upsert
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE kind IN (:kinds) AND accountPubkey = :accountPubkey ORDER BY createdAt DESC LIMIT :limit")
    fun observeByKinds(kinds: List<Int>, accountPubkey: String, limit: Int = 500): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE kind IN (:kinds) AND accountPubkey = :accountPubkey ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByKinds(kinds: List<Int>, accountPubkey: String, limit: Int): List<EventEntity>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<EventEntity>

    @Query("SELECT * FROM events WHERE kind = 1 AND (lower(tagsJson) LIKE '%tbonemusic%' OR lower(tagsJson) LIKE '%tbonepodcast%') ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentAudioCandidates(limit: Int = 400): List<EventEntity>

    /** Latest replaceable event (used for author-published NIP-A3 targets). */
    @Query("SELECT * FROM events WHERE pubkey = :pubkey AND kind = :kind ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestByAuthorAndKind(pubkey: String, kind: Int): EventEntity?

    /** Authored events are needed by notifications after a process restart. */
    @Query("SELECT * FROM events WHERE pubkey = :pubkey ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentByAuthor(pubkey: String, limit: Int): List<EventEntity>

    @Query("SELECT DISTINCT accountPubkey FROM events WHERE accountPubkey != ''")
    suspend fun distinctAccountPubkeys(): List<String>

    /** Prunes one account's cached feed without touching other accounts or own authored notes. */
    @Query(
        "DELETE FROM events WHERE accountPubkey = :accountPubkey AND pubkey != :accountPubkey AND id NOT IN (" +
            "SELECT id FROM events WHERE accountPubkey = :accountPubkey ORDER BY createdAt DESC LIMIT :maxKeep" +
            ")",
    )
    suspend fun keepLatestForAccount(accountPubkey: String, maxKeep: Int): Int

    /** Keeps an account's own authored notes protected up to [maxKeep]. */
    @Query(
        "DELETE FROM events WHERE pubkey = :pubkey AND id NOT IN (" +
            "SELECT id FROM events WHERE pubkey = :pubkey ORDER BY createdAt DESC LIMIT :maxKeep" +
            ")",
    )
    suspend fun keepLatestOwnAuthored(pubkey: String, maxKeep: Int): Int

    /** Prunes shared side-loaded events (quotes, threads, search results). */
    @Query(
        "DELETE FROM events WHERE accountPubkey = '' AND id NOT IN (" +
            "SELECT id FROM events WHERE accountPubkey = '' ORDER BY createdAt DESC LIMIT :maxKeep" +
            ") AND id NOT IN (SELECT id FROM events WHERE accountPubkey = '' AND kind = 1 AND " +
            "(lower(tagsJson) LIKE '%tbonemusic%' OR lower(tagsJson) LIKE '%tbonepodcast%') ORDER BY createdAt DESC LIMIT 100)",
    )
    suspend fun keepLatestShared(maxKeep: Int): Int

    /** Legacy global trim fallback. */
    @Query(
        "DELETE FROM events WHERE id NOT IN (" +
            "SELECT id FROM events ORDER BY createdAt DESC LIMIT :maxKeep" +
            ")",
    )
    suspend fun keepLatest(maxKeep: Int): Int

    @Query("DELETE FROM events")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int
}
