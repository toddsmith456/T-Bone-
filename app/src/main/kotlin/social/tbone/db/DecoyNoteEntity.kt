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
 * Decoy vault — isolated storage for the Notepad front.
 * Same schema as [NoteEntity] but in a completely separate table so the
 * decoy never leaks real notes and vice-versa. Uses the same [social.tbone.notes.NotesCrypto]
 * key for encryption, but the ciphertext lives in a different table.
 */
@Entity(tableName = "decoy_notes")
data class DecoyNoteEntity(
    @PrimaryKey val id: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "note") val kind: String = "note",
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val folderPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sortOrder: Double = 0.0,
    val folderId: String? = null,
    val titleCiphertext: ByteArray? = null,
    val titleIv: ByteArray? = null,
)

@Dao
interface DecoyNoteDao {

    @Query("SELECT * FROM decoy_notes ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    fun observeAll(): Flow<List<DecoyNoteEntity>>

    @Query("SELECT * FROM decoy_notes WHERE id = :id")
    suspend fun getById(id: String): DecoyNoteEntity?

    @Query("SELECT * FROM decoy_notes ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC")
    suspend fun getAll(): List<DecoyNoteEntity>

    @Query("DELETE FROM decoy_notes")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: DecoyNoteEntity)

    @Query("DELETE FROM decoy_notes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM decoy_notes WHERE kind = 'note'")
    fun observeNoteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM decoy_notes WHERE kind = 'checklist'")
    fun observeChecklistCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(LENGTH(ciphertext)), 0) FROM decoy_notes")
    fun observeTotalCiphertextBytes(): Flow<Long>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM decoy_notes")
    suspend fun maxSortOrder(): Double

    @Query("SELECT COALESCE(MIN(sortOrder), 0) FROM decoy_notes")
    suspend fun minSortOrder(): Double

    @Query("UPDATE decoy_notes SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE decoy_notes SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Double)

    @Query("UPDATE decoy_notes SET folderId = :folderId, folderPinned = 0 WHERE id = :id")
    suspend fun setFolder(id: String, folderId: String?)

    @Query("UPDATE decoy_notes SET folderPinned = :pinned WHERE id = :id")
    suspend fun setFolderPinned(id: String, pinned: Boolean)

    @Query("UPDATE decoy_notes SET folderId = NULL, folderPinned = 0 WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: String)
}
