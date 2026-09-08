package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A named folder that can hold any number of notes and checklists.
 * Only the name is stored — the folder itself has no secret content.
 */
@Entity(tableName = "note_folders")
data class NoteFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Pinned folders sort to the top. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Manual ordering — move up/down swaps these between neighbours. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Dao
interface NoteFolderDao {

    @Query("SELECT * FROM note_folders ORDER BY pinned DESC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<NoteFolderEntity>>

    @Query("SELECT * FROM note_folders WHERE id = :id")
    suspend fun getById(id: String): NoteFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: NoteFolderEntity)

    @Query("DELETE FROM note_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM note_folders")
    suspend fun minSortOrder(): Double

    @Query("UPDATE note_folders SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Double)

    @Query("UPDATE note_folders SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE note_folders SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)
}
