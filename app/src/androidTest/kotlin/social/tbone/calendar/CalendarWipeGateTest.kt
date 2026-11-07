package social.tbone.calendar

import android.content.Context
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
}
