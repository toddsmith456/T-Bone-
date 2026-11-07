package social.tbone.calendar

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarWipeGateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun clear() { context.getSharedPreferences("tbone_calendar_alarms", Context.MODE_PRIVATE).edit().clear().commit() }
    @Before fun setUp() = clear()
    @After fun tearDown() = clear()

    @Test fun wipeGateSurvivesSchedulerReconstructionAndReopensOnlyOnSuccess() {
        val first = CalendarAlarmScheduler(context)
        var delivered = 0
        first.beginSensitiveWipe()
        first.deliverIfAllowed { delivered++ }
        val afterRestart = CalendarAlarmScheduler(context)
        afterRestart.deliverIfAllowed { delivered++ }
        assertEquals(0, delivered)
        afterRestart.finishSensitiveWipe()
        afterRestart.deliverIfAllowed { delivered++ }
        assertEquals(1, delivered)
    }

    @Test fun wipeCancelsPreviouslyPostedCalendarNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CalendarAlarmReceiver.CHANNEL_ID,
            "Calendar test", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(314159, Notification.Builder(context, CalendarAlarmReceiver.CHANNEL_ID)
            .setContentTitle("private title")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        assertEquals(1, manager.activeNotifications.count { it.id == 314159 })
        val scheduler = CalendarAlarmScheduler(context)
        scheduler.beginSensitiveWipe()
        assertEquals(0, manager.activeNotifications.count { it.id == 314159 })
        scheduler.finishSensitiveWipe()
    }
}
