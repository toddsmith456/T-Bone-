package social.tbone.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules fully offline, time-based alarms for calendar events. The Android
 * AlarmManager owns the timers; this does not require Google Play Services,
 * relay connectivity, or a persistent app process.
 *
 * Each event gets one pending alarm for its next occurrence: timed events
 * notify ten minutes early, while all-day events notify at local midnight.
 * When the alarm fires,
 * [CalendarAlarmReceiver] posts a notification. Alarms are re-scheduled
 * whenever the event list changes and after a reboot (BOOT_COMPLETED) so an
 * event reminder survives app restarts and device reboots with no network.
 *
 * Alarm delivery is checked against the current database and wipe gate before
 * showing a title, so an obsolete PendingIntent cannot reveal deleted content.
 */
@Singleton
class CalendarAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val alarmManager: AlarmManager?
        get() = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    // A persisted gate survives process death during a wipe. Synchronizing the
    // scheduling and delivery entry points also prevents in-process races.
    private val wipeState = social.tbone.notes.PersistentWipeState(context)
    private var wipeBlocked = prefs().getBoolean("wipe_in_progress", false) || wipeState.read().isNotEmpty()

    // The durable request is written before the coordinator drains work. An
    // already-created scheduler must respect it during that drain as well.
    private fun isBlocked(): Boolean = wipeBlocked || wipeState.read().isNotEmpty()

    @Synchronized fun beginSensitiveWipe() {
        wipeBlocked = true
        check(prefs().edit().putBoolean("wipe_in_progress", true).commit())
        cancelAll()
    }

    @Synchronized fun finishSensitiveWipe() {
        check(prefs().edit().remove("wipe_in_progress").commit())
        wipeBlocked = false
    }

    @Synchronized fun deliverIfAllowed(action: () -> Unit) {
        if (!isBlocked()) action()
    }

    /** How far before the event start the reminder fires. */
    private val LEAD_MILLIS = CalendarAlarmTiming.DEFAULT_LEAD_MILLIS

    /**
     * Cancels every pending calendar alarm, then schedules the NEXT occurrence
     * of each repeating/upcoming event. Only one alarm per event is pending at
     * a time; when it fires, [scheduleNextOccurrence] chains the following one.
     */
    @Synchronized fun rescheduleAll(events: List<CalendarEventUi>) {
        if (isBlocked()) return
        val now = System.currentTimeMillis()
        val upcoming = events.mapNotNull { event ->
            val start = event.nextOccurrenceAtOrAfter(now) ?: return@mapNotNull null
            val lead = if (event.allDay) 0L else LEAD_MILLIS
            val fireAt = CalendarAlarmTiming.reminderFireAt(start, now, lead)
            Triple(event, start, fireAt)
        }
        val activeIds = upcoming.map { it.first.id }.toSet()
        cancelAllExcept(activeIds)
        upcoming.forEach { (event, start, fireAt) ->
            schedule(event.id, event.title, start, fireAt)
        }
    }

    /** Schedules the occurrence of [event] that comes after [afterMillis]. */
    @Synchronized fun scheduleNextOccurrence(event: CalendarEventUi, afterMillis: Long) {
        if (isBlocked()) return
        val start = event.nextOccurrenceAtOrAfter(afterMillis + 1) ?: return
        val now = System.currentTimeMillis()
        val lead = if (event.allDay) 0L else LEAD_MILLIS
        val fireAt = CalendarAlarmTiming.reminderFireAt(start, now, lead)
        schedule(event.id, event.title, start, fireAt)
        persistIds(scheduledIds() + event.id)
    }

    /** Cancel all tracked calendar alarms and their already-posted notifications. */
    @Synchronized fun cancelAll() {
        val ids = scheduledIds().toSet()
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Exception) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        ids.forEach { id ->
            attempt { cancelCurrentPendingAlarm(id) }
            attempt { cancelLegacyPendingAlarm(id) }
        }
        attempt {
            val manager = checkNotNull(context.getSystemService(android.app.NotificationManager::class.java))
            // Some already-posted notifications may outlive their scheduled ID
            // (for example, when the event was deleted after it fired).
            manager.activeNotifications.filter { it.notification.channelId == CalendarAlarmReceiver.CHANNEL_ID }
                .forEach { manager.cancel(it.tag, it.id) }
        }
        failure?.let { throw it }
        persistIds(emptySet())
    }

    @Synchronized fun cancel(eventId: String) {
        cancelCurrentPendingAlarm(eventId)
        cancelLegacyPendingAlarm(eventId)
        persistIds(scheduledIds() - eventId)
    }

    private fun cancelAllExcept(keep: Set<String>) {
        val previousIds = scheduledIds()
        previousIds.forEach { id ->
            // Before the data-URI identity was added, updates would otherwise
            // leave the old PendingIntent alive beside its replacement.
            cancelLegacyPendingAlarm(id)
            if (id !in keep) cancelCurrentPendingAlarm(id)
        }
        persistIds(keep)
    }

    private fun cancelCurrentPendingAlarm(eventId: String) {
        val intent = buildAlarmIntent(context, eventId, "", 0L)
            .setData(Uri.parse("tbone-calendar://alarm/${Uri.encode(eventId)}"))
        cancelPending(eventId, intent)
    }

    private fun cancelLegacyPendingAlarm(eventId: String) {
        cancelPending(eventId, buildAlarmIntent(context, eventId, "", 0L))
    }

    private fun cancelPending(eventId: String, intent: Intent) {
        val pending = PendingIntent.getBroadcast(context, eventId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        val manager = checkNotNull(alarmManager) { "AlarmManager unavailable" }
        manager.cancel(pending)
        pending.cancel()
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
        check(prefs().edit().putStringSet("scheduled", ids).commit())
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
            // The data URI makes PendingIntent identity collision-safe even if
            // two event ids happen to have the same String.hashCode().
            val intent = buildAlarmIntent(context, eventId, title, startMillis)
                .setData(Uri.parse("tbone-calendar://alarm/${Uri.encode(eventId)}"))
            return PendingIntent.getBroadcast(
                context,
                eventId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
