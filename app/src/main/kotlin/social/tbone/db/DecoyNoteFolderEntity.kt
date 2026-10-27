package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "decoy_note_folders")
data class DecoyNoteFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sortOrder: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface DecoyNoteFolderDao {

    @Query("SELECT * FROM decoy_note_folders ORDER BY pinned DESC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<DecoyNoteFolderEntity>>

    @Query("SELECT * FROM decoy_note_folders WHERE id = :id")
    suspend fun getById(id: String): DecoyNoteFolderEntity?

    @Query("SELECT * FROM decoy_note_folders ORDER BY pinned DESC, sortOrder ASC, name ASC")
    suspend fun getAll(): List<DecoyNoteFolderEntity>

    @Query("DELETE FROM decoy_note_folders")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: DecoyNoteFolderEntity)

    @Query("DELETE FROM decoy_note_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM decoy_note_folders")
    suspend fun minSortOrder(): Double

    @Query("UPDATE decoy_note_folders SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Double)

    @Query("UPDATE decoy_note_folders SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE decoy_note_folders SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)
}
