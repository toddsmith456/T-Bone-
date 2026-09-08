package social.tbone.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProfileDao {

    @Upsert
    suspend fun upsert(profile: ProfileEntity)

    @Query("SELECT * FROM profiles ORDER BY createdAt DESC LIMIT 500")
    suspend fun getAll(): List<ProfileEntity>
}
