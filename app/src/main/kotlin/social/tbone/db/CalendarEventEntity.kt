package social.tbone.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A calendar event. Title + description are AES-256-GCM encrypted with a key
 * in the Android Keystore (CalendarCrypto); only ciphertext + IV are stored.
 *
 * The `startMillis` index MUST match the index created in MIGRATION_8_9 —
 * Room compares indices when validating a migration, and a mismatch makes the
 * database fail to open (instant crash at startup).
 */
@Entity(
    tableName = "calendar_events",
    indices = [Index(value = ["startMillis"])],
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val titleCiphertext: ByteArray,
    val titleIv: ByteArray,
    val descriptionCiphertext: ByteArray?,
    val descriptionIv: ByteArray?,
    /** Epoch millis (UTC) of the event start, in the user's local zone. */
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Recurrence: "NONE" | "DAYS" | "WEEKS" | "MONTHS" | "YEARS". */
    @ColumnInfo(defaultValue = "NONE") val repeatUnit: String = "NONE",
    /** How often to repeat (e.g. every 2 weeks). */
    @ColumnInfo(defaultValue = "0") val repeatInterval: Int = 0,
    /** Repeats stop at this epoch millis (end of the chosen day); null = no end date. */
    val repeatEndMillis: Long? = null,
)

@Dao
interface CalendarEventDao {

    @Query("SELECT * FROM calendar_events ORDER BY startMillis ASC")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: String): CalendarEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String)
}
