package social.tbone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ProfileDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(profile: ProfileEntity)

    @Query(
        """UPDATE profiles
            SET contentJson = :contentJson,
                createdAt = :createdAt,
                lastAccessedAt = MAX(lastAccessedAt, :lastAccessedAt),
                fetchedAt = MAX(fetchedAt, :fetchedAt),
                isFollowed = CASE WHEN :isFollowed = 1 THEN 1 ELSE isFollowed END,
                searchText = :searchText
            WHERE pubkey = :pubkey AND createdAt < :createdAt""",
    )
    abstract suspend fun updateIfNewer(
        pubkey: String,
        contentJson: String,
        createdAt: Long,
        lastAccessedAt: Long,
        fetchedAt: Long,
        isFollowed: Boolean,
        searchText: String,
    ): Int

    /** Refreshes SWR timestamps when relays confirm an unchanged kind-0 createdAt. */
    @Query(
        """UPDATE profiles
            SET fetchedAt = MAX(fetchedAt, :fetchedAt),
                lastAccessedAt = MAX(lastAccessedAt, :lastAccessedAt),
                isFollowed = CASE WHEN :isFollowed = 1 THEN 1 ELSE isFollowed END,
                searchText = CASE WHEN searchText = '' THEN :searchText ELSE searchText END
            WHERE pubkey = :pubkey AND createdAt = :createdAt""",
    )
    abstract suspend fun refreshTimestampsIfSame(
        pubkey: String,
        createdAt: Long,
        fetchedAt: Long,
        lastAccessedAt: Long,
        isFollowed: Boolean,
        searchText: String,
    ): Int

    /** Older relay responses and queued IO writes must never replace newer metadata. */
    @Transaction
    open suspend fun upsertIfNewer(profile: ProfileEntity) {
        val updated = updateIfNewer(
            pubkey = profile.pubkey,
            contentJson = profile.contentJson,
            createdAt = profile.createdAt,
            lastAccessedAt = profile.lastAccessedAt,
            fetchedAt = profile.fetchedAt,
            isFollowed = profile.isFollowed,
            searchText = profile.searchText,
        )
        if (updated == 0) {
            refreshTimestampsIfSame(
                pubkey = profile.pubkey,
                createdAt = profile.createdAt,
                fetchedAt = profile.fetchedAt,
                lastAccessedAt = profile.lastAccessedAt,
                isFollowed = profile.isFollowed,
                searchText = profile.searchText,
            )
            insertIfAbsent(profile)
        }
    }

    /** Batched variant: writes an entire burst of profiles in one SQLite transaction. */
    @Transaction
    open suspend fun upsertAllIfNewer(profiles: List<ProfileEntity>) {
        for (profile in profiles) {
            upsertIfNewer(profile)
        }
    }

    @Query("SELECT * FROM profiles ORDER BY isFollowed DESC, lastAccessedAt DESC, createdAt DESC LIMIT :limit")
    abstract suspend fun getWarmProfiles(limit: Int): List<ProfileEntity>

    @Query("SELECT * FROM profiles ORDER BY isFollowed DESC, lastAccessedAt DESC, createdAt DESC LIMIT 500")
    abstract suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE pubkey = :pubkey LIMIT 1")
    abstract suspend fun getByPubkey(pubkey: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE pubkey IN (:pubkeys)")
    abstract suspend fun getByPubkeys(pubkeys: List<String>): List<ProfileEntity>

    @Query("UPDATE profiles SET lastAccessedAt = MAX(lastAccessedAt, :accessedAt) WHERE pubkey IN (:pubkeys)")
    abstract suspend fun touchAccessed(pubkeys: List<String>, accessedAt: Long)

    @Query("UPDATE profiles SET isFollowed = 0 WHERE isFollowed = 1")
    abstract suspend fun clearFollowedFlags()

    @Query("UPDATE profiles SET isFollowed = 1 WHERE pubkey IN (:pubkeys)")
    abstract suspend fun markFollowed(pubkeys: List<String>)

    @Transaction
    open suspend fun syncFollowedFlags(followedPubkeys: Collection<String>) {
        clearFollowedFlags()
        val unique = followedPubkeys.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        for (chunk in unique.chunked(500)) {
            markFollowed(chunk)
        }
    }

    @Query(
        "SELECT * FROM profiles WHERE searchText LIKE :likePattern ESCAPE '\\' " +
            "ORDER BY isFollowed DESC, lastAccessedAt DESC, createdAt DESC LIMIT :limit",
    )
    abstract suspend fun searchByText(likePattern: String, limit: Int): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE searchText = '' LIMIT :limit")
    abstract suspend fun getRowsMissingSearchText(limit: Int): List<ProfileEntity>

    @Query("UPDATE profiles SET searchText = :searchText WHERE pubkey = :pubkey")
    abstract suspend fun updateSearchText(pubkey: String, searchText: String)

    @Transaction
    open suspend fun updateSearchTexts(updates: List<Pair<String, String>>) {
        for ((pubkey, text) in updates) {
            updateSearchText(pubkey, text)
        }
    }

    /**
     * Keep followed accounts pinned first, then most-recently-seen profiles,
     * evicting stale unfollowed strangers first.
     */
    @Query(
        "DELETE FROM profiles WHERE pubkey NOT IN (" +
            "SELECT pubkey FROM profiles " +
            "ORDER BY isFollowed DESC, lastAccessedAt DESC, createdAt DESC LIMIT :maxKeep" +
            ")",
    )
    abstract suspend fun keepLatest(maxKeep: Int): Int

    @Query("DELETE FROM profiles WHERE isFollowed = 0")
    abstract suspend fun deleteUnfollowed(): Int

    @Query("DELETE FROM profiles")
    abstract suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM profiles")
    abstract suspend fun count(): Int
}
