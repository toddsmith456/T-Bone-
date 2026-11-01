package social.tbone.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundPinTimeoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private class Clock : PinClock {
        var elapsed = 100_000L
        var wall = 1_700_000_000_000L
        var boot = 1
        override fun elapsed() = elapsed
        override fun wall() = wall
        override fun bootCount() = boot
        fun advance(ms: Long) { elapsed += ms; wall += ms }
    }
    private val clock = Clock()
    @Before fun clear() { context.getSharedPreferences("pin_background_timeout", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun tearDown() { scope.cancel(); clear() }
    private fun timer() = BackgroundPinTimeout(context, scope, "test", clock)

    @Test fun foregroundUsageDoesNotExpireButBackgroundDoes() {
        val pin = timer()
        pin.onForeground(); pin.initialize(true, 1); pin.unlock()
        clock.advance(5 * 60_000L)
        pin.settingsChanged(true, 1)
        assertFalse(pin.locked.value)
        pin.onBackground(); clock.advance(59_999L); pin.onForeground()
        assertFalse(pin.locked.value)
        pin.onBackground(); clock.advance(60_000L); pin.onForeground()
        assertTrue(pin.locked.value)
    }

    @Test fun processRestartRespectsBackgroundGraceAndFailsClosedAfterReboot() {
        val first = timer()
        first.onForeground(); first.initialize(true, 1); first.unlock(); first.onBackground()
        clock.advance(30_000L)
        val restored = timer()
        restored.onForeground(); restored.initialize(true, 1)
        assertFalse(restored.locked.value)
        restored.onBackground(); clock.boot++
        val restarted = timer()
        restarted.onForeground(); restarted.initialize(true, 1)
        assertTrue(restarted.locked.value)
    }

    @Test fun manualLockCannotBeUndoneByForegroundTransition() {
        val pin = timer()
        pin.onForeground(); pin.initialize(true, 1); pin.unlock()
        pin.lock(); pin.onBackground(); pin.onForeground()
        assertTrue(pin.locked.value)
    }
    @Test fun processDeathWhileForegroundedFailsClosed() {
        val first = timer()
        first.onForeground(); first.initialize(true, 1); first.unlock()
        clock.advance(20 * 60_000L)
        assertFalse(first.locked.value)
        val restarted = timer()
        restarted.onForeground(); restarted.initialize(true, 1)
        assertTrue(restarted.locked.value)
    }

}
