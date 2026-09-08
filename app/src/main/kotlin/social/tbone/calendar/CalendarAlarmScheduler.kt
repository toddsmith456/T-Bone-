package social.tbone.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules fully offline, time-based alarms for calendar events.
 *
 * Each event with a future end time gets an exact alarm at its start time
 * (all-day events alarm at local midnight). When the alarm fires,
 * [CalendarAlarmReceiver] posts a notification. Alarms are re-scheduled
 * whenever the event list changes and after a reboot (BOOT_COMPLETED) so an
 * event reminder survives app restarts and device reboots with no network.
 *
 * The alarm intent carries the (decrypted) title + start time as extras, so
 * the receiver can show a proper notification without touching the database.
 */
@Singleton
class CalendarAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** How far before the event start the reminder fires. */
    private val LEAD_MILLIS = 10L * 60L * 1000L

    /**
     * Cancels every pending calendar alarm, then schedules the NEXT occurrence
     * of each repeating/upcoming event. Only one alarm per event is pending at
     * a time; when it fires, [scheduleNextOccurrence] chains the following one.
     */
    fun rescheduleAll(events: List<CalendarEventUi>) {
        val now = System.currentTimeMillis()
        val upcoming = events.mapNotNull { event ->
            val start = event.nextOccurrenceAtOrAfter(now + LEAD_MILLIS) ?: return@mapNotNull null
            event to start
        }
        val activeIds = upcoming.map { it.first.id }.toSet()
        cancelAllExcept(activeIds)
        upcoming.forEach { (event, start) ->
            schedule(event.id, event.title, start, start - LEAD_MILLIS)
        }
    }

    /** Schedules the occurrence of [event] that comes after [afterMillis]. */
    fun scheduleNextOccurrence(event: CalendarEventUi, afterMillis: Long) {
        val start = event.nextOccurrenceAtOrAfter(afterMillis + 1) ?: return
        val fireAt = start - LEAD_MILLIS
        if (fireAt <= System.currentTimeMillis()) return
        schedule(event.id, event.title, start, fireAt)
        persistIds(scheduledIds() + event.id)
    }

    fun cancel(eventId: String) {
        runCatching { alarmManager?.cancel(pi(context, eventId, "", 0L)) }
        persistIds(scheduledIds() - eventId)
    }

    private fun cancelAllExcept(keep: Set<String>) {
        scheduledIds().filterNot { it in keep }.forEach { id ->
            runCatching { alarmManager?.cancel(pi(context, id, "", 0L)) }
        }
        persistIds(keep)
    }

    private fun schedule(eventId: String, title: String, startMillis: Long, fireAt: Long) {
        val manager = alarmManager ?: return
        if (fireAt <= System.currentTimeMillis()) return
        val pi = pi(context, eventId, title, startMillis)
        try {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                manager.canScheduleExactAlarms()
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                // No exact-alarm permission (user hasn't granted "Alarms &
                // reminders") — degrade gracefully to an inexact alarm.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        } catch (e: SecurityException) {
            // Last resort — never crash.
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        }
    }

    private fun scheduledIds(): Set<String> =
        prefs().getStringSet("scheduled", emptySet()) ?: emptySet()

    private fun persistIds(ids: Set<String>) {
        prefs().edit().putStringSet("scheduled", ids).apply()
    }

    private fun prefs() =
        context.getSharedPreferences("tbone_calendar_alarms", Context.MODE_PRIVATE)

    companion object {
        // FORK: unique action so alarms from the original T-Bone
        // (social.tbone) and this fork never cross-fire when both are installed.
        const val ACTION_ALARM = "social.tbone.fork.CALENDAR_ALARM"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START = "start"

        /** Alarm carries the event payload so the receiver needs no DB access. */
        fun buildAlarmIntent(
            context: Context,
            eventId: String,
            title: String,
            startMillis: Long,
        ): Intent = Intent(context, CalendarAlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_START, startMillis)

        private fun pi(
            context: Context,
            eventId: String,
            title: String,
            startMillis: Long,
        ): PendingIntent {
            val intent = buildAlarmIntent(context, eventId, title, startMillis)
            return PendingIntent.getBroadcast(
                context,
                eventId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
