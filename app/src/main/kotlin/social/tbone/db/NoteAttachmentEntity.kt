package social.tbone.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * An attachment (voice recording or image) stored inline in a note's text.
 * The raw bytes are AES-256-GCM encrypted with the same notes key before they
 * touch disk; only ciphertext + IV live in the database.
 */
@Entity(
    tableName = "note_attachments",
    indices = [Index(value = ["noteId"])],
)
data class NoteAttachmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    /** "voice" or "image". */
    val kind: String,
    val mime: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val size: Long,
    val createdAt: Long,
)

@Dao
interface NoteAttachmentDao {

    @Query("SELECT * FROM note_attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observeByNote(noteId: String): Flow<List<NoteAttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: NoteAttachmentEntity)

    @Query("DELETE FROM note_attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM note_attachments WHERE noteId = :noteId")
    suspend fun deleteByNote(noteId: String)
}
