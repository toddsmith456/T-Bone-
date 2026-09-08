package social.tbone.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import social.tbone.MainActivity
import social.tbone.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receives calendar alarms and posts the offline event notification. Also
 * handles BOOT_COMPLETED / MY_PACKAGE_REPLACED by re-scheduling all alarms so
 * reminders survive reboots and updates.
 */
@AndroidEntryPoint
class CalendarAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: CalendarRepository
    @Inject lateinit var scheduler: CalendarAlarmScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CalendarAlarmScheduler.ACTION_ALARM -> {
                val id = intent.getStringExtra(CalendarAlarmScheduler.EXTRA_EVENT_ID)
                    ?: return
                val title = intent.getStringExtra(CalendarAlarmScheduler.EXTRA_TITLE)
                    ?: "calendar event"
                val start = intent.getLongExtra(CalendarAlarmScheduler.EXTRA_START, 0L)
                postNotification(context, title, start)
                // Chain the NEXT occurrence (repeating events keep notifying
                // without the app being opened in between).
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val event = runCatching { repository.getById(id) }.getOrNull()
                        if (event != null) scheduler.scheduleNextOccurrence(event, start)
                    } finally {
                        pending.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                // Re-schedule future alarms after reboot / update. The DB read
                // is suspend, so run it on an IO coroutine and finish when done.
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val events = runCatching { repository.getEventsForScheduling() }
                            .getOrDefault(emptyList())
                        scheduler.rescheduleAll(events)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun postNotification(context: Context, title: String, startMillis: Long) {
        // Android 13+ needs POST_NOTIFICATIONS before showing anything.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val time = Instant.ofEpochMilli(startMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a"))
        val contentIntent = PendingIntent.getActivity(
            context,
            startMillis.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_calendar)
            .setContentTitle(title)
            .setContentText("starts at $time")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(startMillis.toInt(), notification)
        }
    }

    private fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Calendar events",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Offline reminders for your calendar events" },
        )
    }

    companion object {
        const val CHANNEL_ID = "calendar"
    }
}
