package social.tbone.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import social.tbone.calendar.CalendarAlarmScheduler

@RunWith(AndroidJUnit4::class)
class PersistentWipeStateTest {
    @Test fun markerSurvivesNewStoreAndBlocksCalendarDelivery() {
        // Instrumentation package, isolated from the app's real recovery marker.
        val context = InstrumentationRegistry.getInstrumentation().context
        val state = PersistentWipeState(context)
        assertTrue(state.write(emptySet()))
        try {
            val existingScheduler = CalendarAlarmScheduler(context)
            assertTrue(state.write(setOf(NoteWriteCoordinator.WipeKind.APP)))
            assertEquals(setOf(NoteWriteCoordinator.WipeKind.APP), PersistentWipeState(context).read())
            val scheduler = CalendarAlarmScheduler(context)
            var delivered = false
            existingScheduler.deliverIfAllowed { delivered = true }
            assertFalse(delivered)
            scheduler.deliverIfAllowed { delivered = true }
            assertFalse(delivered)
            assertTrue(state.write(emptySet()))
            val newScheduler = CalendarAlarmScheduler(context)
            newScheduler.deliverIfAllowed { delivered = true }
            assertTrue(delivered)
        } finally {
            state.write(emptySet())
        }
    }

    @Test fun invalidMarkerFailsClosed() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val prefs = context.getSharedPreferences(PersistentWipeState.PREFS_NAME, 0)
        try {
            assertTrue(prefs.edit().putStringSet("pending_kinds", setOf("UNKNOWN_WIPE")).commit())
            assertEquals(setOf(NoteWriteCoordinator.WipeKind.APP), PersistentWipeState(context).read())
        } finally { prefs.edit().clear().commit() }
    }
}
