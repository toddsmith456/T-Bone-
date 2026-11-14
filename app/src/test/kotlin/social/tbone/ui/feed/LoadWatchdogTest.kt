package social.tbone.ui.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The watchdog's contract, which is the fix for the infinite spinner.
 *
 * The failure being prevented is specific: a settle path that stopped the loader
 * from waiting but never cleared `isLoading`, so the screen spun forever with
 * every relay connected. These tests pin the two properties that make that
 * impossible — a timeout always reaches its callback, and a finished load never
 * does.
 */
class LoadWatchdogTest {

    private lateinit var scope: CoroutineScope

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun aLoadThatNeverFinishesAlwaysReachesItsTimeout() = runBlocking {
        val watchdog = LoadWatchdog(scope, "nothing came back")
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        watchdog.start(
            timeoutMs = 40,
            isEmpty = { true },
            onTimeout = { fired.incrementAndGet() },
        )
        delay(300)
        assertEquals("a timeout must always fire, or the spinner never ends", 1, fired.get())
    }

    @Test fun aFinishedLoadNeverFiresItsTimeout() = runBlocking {
        val watchdog = LoadWatchdog(scope, "nothing came back")
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        watchdog.start(
            timeoutMs = 40,
            isEmpty = { true },
            onTimeout = { fired.incrementAndGet() },
        )
        watchdog.finish()
        delay(300)
        assertEquals(0, fired.get())
    }

    @Test fun finishingLateDoesNotDoubleFire() = runBlocking {
        val watchdog = LoadWatchdog(scope, "nothing came back")
        val fired = java.util.concurrent.atomic.AtomicInteger(0)
        watchdog.start(20) { fired.incrementAndGet() }
        delay(150)
        watchdog.finish()
        watchdog.finish()
        delay(50)
        assertEquals(1, fired.get())
    }

    @Test fun aSupersededLoadCannotPublishOverItsReplacement() = runBlocking {
        // The reported hang came from a second load starting while the first was
        // still in flight: the first load's timer must not be able to end the
        // second load's spinner with a stale message.
        val watchdog = LoadWatchdog(scope, "nothing came back")
        val first = java.util.concurrent.atomic.AtomicInteger(0)
        val second = java.util.concurrent.atomic.AtomicInteger(0)
        watchdog.start(30) { first.incrementAndGet() }
        delay(10)
        watchdog.start(60) { second.incrementAndGet() }
        delay(400)
        assertEquals("the superseded timer must stay silent", 0, first.get())
        assertEquals("the live timer must still fire", 1, second.get())
    }

    @Test fun theTimeoutKnowsWhetherTheFeedIsEmpty() = runBlocking {
        val watchdog = LoadWatchdog(scope, "nothing came back")
        var sawEmpty: Boolean? = null
        watchdog.start(20, isEmpty = { true }) { sawEmpty = it }
        delay(150)
        assertEquals(true, sawEmpty)
    }

    @Test fun aFeedWithContentThatTimedOutIsNotReportedAsEmpty() = runBlocking {
        val watchdog = LoadWatchdog(scope, "nothing came back")
        var sawEmpty: Boolean? = null
        watchdog.start(20, isEmpty = { false }) { sawEmpty = it }
        delay(150)
        assertEquals(false, sawEmpty)
    }

    @Test fun theEmptyMessageIsAlwaysAvailableToSayInsteadOfSpinning() {
        val watchdog = LoadWatchdog(scope, "No notes came back in time.")
        assertTrue(watchdog.emptyMessage().isNotBlank())
    }

    @Test fun finishBeforeStartIsHarmless() {
        // Every settle path calls finish() unconditionally, including ones that
        // can run before a load was ever armed (a blocked layout, an empty
        // account). It must not throw.
        val watchdog = LoadWatchdog(scope, "x")
        watchdog.finish()
        watchdog.finish()
    }
}
