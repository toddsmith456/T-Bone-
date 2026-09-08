package social.tbone.calendar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import social.tbone.db.CalendarEventDao
import social.tbone.db.CalendarEventEntity
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How a calendar event repeats. */
enum class RepeatUnit(val label: String) {
    NONE("never"),
    DAYS("days"),
    WEEKS("weeks"),
    MONTHS("months"),
    YEARS("years"),
    ;

    companion object {
        fun fromDb(raw: String): RepeatUnit = entries.firstOrNull { it.name == raw } ?: NONE
    }
}

/** The calendar ends at the end of 2050 — occurrences stop there. */
private const val REPEAT_END_YEAR = 2050

/** A decrypted calendar event for the UI, with recurrence. */
data class CalendarEventUi(
    val id: String,
    val title: String,
    val description: String,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val repeatUnit: RepeatUnit = RepeatUnit.NONE,
    val repeatInterval: Int = 0,
    val repeatEndMillis: Long? = null,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    val startDate: LocalDate
        get() = Instant.ofEpochMilli(startMillis).atZone(zone).toLocalDate()

    val endDate: LocalDate
        get() = Instant.ofEpochMilli(endMillis).atZone(zone).toLocalDate()

    val isRepeating: Boolean get() = repeatUnit != RepeatUnit.NONE && repeatInterval > 0

    /** The end-of-2050 cap in this zone (occurrences stop there). */
    private fun defaultEndCapMillis(): Long =
        YearMonth.of(REPEAT_END_YEAR, 12).atEndOfMonth()
            .atTime(23, 59).atZone(zone).toInstant().toEpochMilli()

    /** Cap = the chosen repeat end date (end of its day), else the 2050 cap. */
    private fun endCapMillis(): Long {
        val end = repeatEndMillis ?: return defaultEndCapMillis()
        // If the user picked a date, treat it as end-of-day so the last
        // occurrence on that date still counts.
        return Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    }

    private fun step(base: java.time.ZonedDateTime, i: Long): java.time.ZonedDateTime = when (repeatUnit) {
        RepeatUnit.DAYS -> base.plusDays(repeatInterval.toLong() * i)
        RepeatUnit.WEEKS -> base.plusWeeks(repeatInterval.toLong() * i)
        RepeatUnit.MONTHS -> base.plusMonths(repeatInterval.toLong() * i)
        RepeatUnit.YEARS -> base.plusYears(repeatInterval.toLong() * i)
        RepeatUnit.NONE -> base
    }

    /**
     * All occurrence START millis from the base start forward, capped at the
     * end of 2050 (safety cap [maxCount]). A non-repeating event yields exactly
     * one occurrence. Month/year steps use java.time (which clamps e.g. Jan 31
     * + 1 month → Feb 28/29), keeping the same time of day.
     */
    fun occurrenceStarts(maxCount: Int = 100_000): List<Long> {
        val result = ArrayList<Long>()
        result.add(startMillis)
        if (!isRepeating) return result
        val base = Instant.ofEpochMilli(startMillis).atZone(zone)
        val endCap = endCapMillis()
        var i = 1L
        while (i < maxCount) {
            val ms = step(base, i).toInstant().toEpochMilli()
            if (ms > endCap) break
            result.add(ms)
            i++
        }
        return result
    }

    /**
     * Occurrence starts within [fromMillis, toMillis] — walks only as far as
     * needed, so old long-running repeats stay cheap and correct.
     */
    fun occurrenceStartsInRange(fromMillis: Long, toMillis: Long, maxSteps: Int = 100_000): List<Long> {
        if (!isRepeating) {
            return if (startMillis in fromMillis..toMillis) listOf(startMillis) else emptyList()
        }
        val result = ArrayList<Long>()
        val base = Instant.ofEpochMilli(startMillis).atZone(zone)
        val endCap = endCapMillis()
        var i = 0L
        while (i < maxSteps) {
            val ms = step(base, i).toInstant().toEpochMilli()
            if (ms > toMillis || ms > endCap) break
            if (ms >= fromMillis) result.add(ms)
            i++
        }
        return result
    }

    /** True when an occurrence starts on [date]. */
    fun hasOccurrenceOn(date: LocalDate): Boolean {
        if (startDate == date) return true
        if (!isRepeating) return false
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return occurrenceStartsInRange(dayStart, dayEnd).isNotEmpty()
    }

    /** Occurrence starts that land on [date] (for the day sheet). */
    fun occurrenceStartsOn(date: LocalDate): List<Long> {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return occurrenceStartsInRange(dayStart, dayEnd)
    }

    /** The next occurrence start at/after [afterMillis], or null. */
    fun nextOccurrenceAtOrAfter(afterMillis: Long, maxSteps: Int = 100_000): Long? {
        if (!isRepeating) return if (startMillis >= afterMillis) startMillis else null
        val base = Instant.ofEpochMilli(startMillis).atZone(zone)
        val endCap = endCapMillis()
        var i = 0L
        while (i < maxSteps) {
            val ms = step(base, i).toInstant().toEpochMilli()
            if (ms > endCap) return null
            if (ms >= afterMillis) return ms
            i++
        }
        return null
    }

    /** True when the event's base start falls on [date] (used by the old API). */
    fun occursOn(date: LocalDate): Boolean = startDate == date

    /** Human label like "every 2 weeks". */
    fun repeatLabel(): String = when {
        !isRepeating -> "never"
        repeatInterval == 1 -> "every ${repeatUnit.label.dropLast(1)}"
        else -> "every $repeatInterval ${repeatUnit.label}"
    }
}

/**
 * CRUD for the encrypted calendar. Title + description are encrypted with
 * [CalendarCrypto] before they reach Room; only ciphertext + IV are stored.
 * Saves are idempotent by id: passing the same id twice upserts ONE row, so
 * repeated save calls can never create duplicate events (the double-add bug).
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val dao: CalendarEventDao,
    private val crypto: CalendarCrypto,
) {

    /** All events, decrypted, sorted by start time. */
    val events: Flow<List<CalendarEventUi>> = dao.observeAll().map { entities ->
        entities.mapNotNull { entity -> entity.toUi(crypto) }
    }

    fun encryptionSelfTest(): Boolean = crypto.selfTest()

    fun isHardwareBacked(): Boolean = crypto.isHardwareBacked()

    suspend fun getById(id: String): CalendarEventUi? = dao.getById(id)?.let { it.toUi(crypto) }

    /** One-shot decrypted event list — used by the boot alarm re-scheduler. */
    suspend fun getEventsForScheduling(): List<CalendarEventUi> =
        dao.observeAll().first().mapNotNull { it.toUi(crypto) }

    /**
     * Saves (creates or updates) ONE event. When [id] is null a new id is
     * generated. Idempotent: the same id always maps to the same row.
     */
    suspend fun saveEvent(
        id: String?,
        title: String,
        description: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        repeatUnit: RepeatUnit,
        repeatInterval: Int,
        repeatEndMillis: Long?,
    ): String {
        val now = System.currentTimeMillis()
        val (tct, tiv) = crypto.encrypt(title.trim())
        val (dct, div) = if (description.isBlank()) null to null else crypto.encrypt(description.trim())
        val eid = id ?: UUID.randomUUID().toString()
        val existing = dao.getById(eid)
        dao.upsert(
            CalendarEventEntity(
                id = eid,
                titleCiphertext = tct,
                titleIv = tiv,
                descriptionCiphertext = dct,
                descriptionIv = div,
                startMillis = startMillis,
                endMillis = endMillis,
                allDay = allDay,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                repeatUnit = repeatUnit.name,
                repeatInterval = repeatInterval.coerceAtLeast(0),
                repeatEndMillis = repeatEndMillis,
            )
        )
        return eid
    }

    suspend fun deleteEvent(id: String) = dao.delete(id)

    private fun CalendarEventEntity.toUi(crypto: CalendarCrypto): CalendarEventUi? = runCatching {
        CalendarEventUi(
            id = id,
            title = crypto.decrypt(titleCiphertext, titleIv),
            description = descriptionCiphertext?.let { ct ->
                descriptionIv?.let { iv -> runCatching { crypto.decrypt(ct, iv) }.getOrNull() }
            } ?: "",
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            createdAt = createdAt,
            updatedAt = updatedAt,
            repeatUnit = RepeatUnit.fromDb(repeatUnit),
            repeatInterval = repeatInterval,
            repeatEndMillis = repeatEndMillis,
        )
    }.getOrNull()
}
