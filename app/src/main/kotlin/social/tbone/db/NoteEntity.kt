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
 * A stored (encrypted) note or checklist. Only ciphertext + IV ever touch disk.
 * Notes may belong to a folder ([folderId]) and carry an encrypted title like
 * checklists do.
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    /** "note" or "checklist" — same AES-GCM key encrypts both. */
    @ColumnInfo(defaultValue = "note") val kind: String = "note",
    /** Pinned notes sort to the top of the list. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Manual ordering — move up/down swaps these between neighbours. */
    @ColumnInfo(defaultValue = "0") val sortOrder: Double = 0.0,
    /** Parent folder, or null for un-filed notes. */
    val folderId: String? = null,
    /** Encrypted title (checklists keep theirs inside the payload; notes get a title field). */
    val titleCiphertext: ByteArray? = null,
    val titleIv: ByteArray? = null,
)

@Dao
interface NoteDao {

    /** Pinned first, then manual order, then newest-updated as the tiebreak. */
    @Query("SELECT * FROM notes ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM notes WHERE kind = 'note'")
    fun observeNoteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes WHERE kind = 'checklist'")
    fun observeChecklistCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(ciphertext)), 0) FROM notes")
    fun observeTotalCiphertextBytes(): Flow<Long>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM notes")
    suspend fun maxSortOrder(): Double

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM notes")
    suspend fun minSortOrder(): Double

    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE notes SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Double)

    @Query("UPDATE notes SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Query("UPDATE notes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}
