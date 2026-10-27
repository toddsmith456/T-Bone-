package social.tbone.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "decoy_note_attachments",
    indices = [Index(value = ["noteId"])],
)
data class DecoyNoteAttachmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val kind: String,
    val mime: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val size: Long,
    val createdAt: Long,
)

@Dao
interface DecoyNoteAttachmentDao {

    @Query("SELECT * FROM decoy_note_attachments WHERE noteId = :noteId ORDER BY createdAt ASC")
    fun observeByNote(noteId: String): Flow<List<DecoyNoteAttachmentEntity>>

    @Query("SELECT * FROM decoy_note_attachments WHERE id = :id")
    suspend fun getById(id: String): DecoyNoteAttachmentEntity?

    @Query("DELETE FROM decoy_note_attachments")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: DecoyNoteAttachmentEntity)

    @Query("DELETE FROM decoy_note_attachments WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM decoy_note_attachments WHERE noteId = :noteId")
    suspend fun deleteByNote(noteId: String)
}
