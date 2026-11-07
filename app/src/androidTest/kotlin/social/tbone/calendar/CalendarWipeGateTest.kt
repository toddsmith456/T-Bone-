package social.tbone.calendar

import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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

    private fun awaitNotificationCount(manager: NotificationManager, expected: Int) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (manager.activeNotifications.count { it.id == 314159 } != expected &&
            android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertEquals(expected, manager.activeNotifications.count { it.id == 314159 })
    }

    @Test fun wipeCancelsPreviouslyPostedCalendarNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CalendarAlarmReceiver.CHANNEL_ID,
            "Calendar test", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(314159, Notification.Builder(context, CalendarAlarmReceiver.CHANNEL_ID)
            .setContentTitle("private title")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        awaitNotificationCount(manager, 1)
        val scheduler = CalendarAlarmScheduler(context)
        scheduler.beginSensitiveWipe()
        awaitNotificationCount(manager, 0)
        scheduler.finishSensitiveWipe()
    }

    @Test fun staleCalendarEditorEpochCannotRecreateEventAfterWipe() = kotlinx.coroutines.runBlocking {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, social.tbone.db.AppDatabase::class.java).build()
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val writes = social.tbone.notes.NoteWriteCoordinator(owner)
            val crypto = CalendarCrypto()
            val repository = CalendarRepository(db.calendarEventDao(), crypto, writes)
            val vm = social.tbone.ui.toolbox.calendar.CalendarViewModel(repository, crypto, writes)
            kotlinx.coroutines.withTimeout(10_000) {
                vm.encryptionVerified.first { it }
            }
            val staleEpoch = vm.currentWipeEpoch()
            assertEquals(true, writes.runWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.TOOLBOX) {
                writes.pauseAndDrain()
                db.clearAllTables()
                true
            })
            val failed = kotlinx.coroutines.CompletableDeferred<Exception>()
            vm.saveEvent(id = "stale", title = "private", description = "", startMillis = 1L, endMillis = 2L,
                allDay = false, repeatUnit = RepeatUnit.NONE, repeatInterval = 1, repeatEndMillis = null,
                epoch = staleEpoch, onError = { failed.complete(it) })
            kotlinx.coroutines.withTimeout(5_000) { failed.await() }
            assertEquals(null, repository.getById("stale"))
        } finally {
            owner.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            db.close()
        }
    }

}
