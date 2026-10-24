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
            SET contentJson = :contentJson, createdAt = :createdAt
            WHERE pubkey = :pubkey AND createdAt < :createdAt""",
    )
    abstract suspend fun updateIfNewer(pubkey: String, contentJson: String, createdAt: Long): Int

    /** Older relay responses and queued IO writes must never replace newer metadata. */
    @Transaction
    open suspend fun upsertIfNewer(profile: ProfileEntity) {
        updateIfNewer(profile.pubkey, profile.contentJson, profile.createdAt)
        insertIfAbsent(profile)
    }

    @Query("SELECT * FROM profiles ORDER BY createdAt DESC LIMIT 500")
    abstract suspend fun getAll(): List<ProfileEntity>
}
