package social.tbone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The performance section exists so a slow cold start can be diagnosed from a
 * user's shared report instead of from source. These tests pin the two things
 * that matter: the numbers it reports, and the fact that it reports nothing that
 * identifies the user.
 */
class StartupMetricsTest {

    private lateinit var metrics: StartupMetrics

    @Before
    fun setUp() {
        metrics = StartupMetrics()
        metrics.reset()
    }

    @Test
    fun `reports every counter it was given`() {
        metrics.onProcessStart()
        val start = metrics.onFeedLoadStart()
        metrics.feedFirstEvent(start)
        metrics.relaySnapshot(events = 1_234, dropped = 7, subscriptions = 5, relays = 4)
        metrics.verification(cheap = false)
        metrics.verification(cheap = true)
        metrics.metadataBatch(1_500)

        val report = metrics.report()
        assertTrue(report.contains("Process launches: 1"))
        assertTrue(report.contains("Feed loads: 1"))
        assertTrue(report.contains("1,234") || report.contains("1234"))
        assertTrue(report.contains("dropped = 7") || report.contains("7 displaced"))
        assertTrue(report.contains("1 full"))
        assertTrue(report.contains("1 cache hits"))
        assertTrue(report.contains("median 1500 ms"))
    }

    @Test
    fun `a timeout with backpressure points at the pipe, not at the relays`() {
        val start = metrics.onFeedLoadStart()
        metrics.feedTimedOut(start)
        metrics.relaySnapshot(events = 900, dropped = 42, subscriptions = 16, relays = 8)

        val report = metrics.report()
        assertTrue(
            "a timed-out load with displaced events must name backpressure",
            report.contains("timed out") && report.contains("42"),
        )
        assertTrue(report.contains("backpressure is the bottleneck"))
    }

    @Test
    fun `a timeout without backpressure points at relay reachability`() {
        val start = metrics.onFeedLoadStart()
        metrics.feedTimedOut(start)
        metrics.relaySnapshot(events = 0, dropped = 0, subscriptions = 2, relays = 0)

        assertTrue(metrics.report().contains("relay reachability"))
    }

    @Test
    fun `slow but successful loads are reported as latency, not CPU`() {
        val start = metrics.onFeedLoadStart()
        metrics.feedFirstEvent(start)
        // Simulate a completed-but-slow load by recording a synthetic sample.
        metrics.metadataBatch(9_500)
        val report = metrics.report()
        assertTrue(
            "nothing should be blamed on the CPU when a load completed",
            report.contains("healthy") || report.contains("relay latency"),
        )
    }

    @Test
    fun `an empty session says so instead of inventing a verdict`() {
        assertTrue(metrics.report().contains("not enough data yet"))
    }

    @Test
    fun `the report never contains identifying data`() {
        metrics.onProcessStart()
        val start = metrics.onFeedLoadStart()
        metrics.feedFirstEvent(start)
        metrics.relaySnapshot(events = 10, dropped = 0, subscriptions = 1, relays = 1)
        val report = metrics.report()

        // No note text, no keys, no relay URLs, no file paths.
        listOf("ws://", "wss://", "npub", "nsec", "pubkey", "/data/", "/home/").forEach { needle ->
            assertFalse("performance report must not contain '$needle'", report.contains(needle))
        }
        // A 64-char lowercase hex run would be an event id or pubkey.
        assertFalse(
            "performance report must not contain hex identifiers",
            Regex("[0-9a-f]{64}").containsMatchIn(report),
        )
    }

    @Test
    fun `samples are bounded so a long session cannot grow the report`() {
        repeat(500) { index ->
            val start = metrics.onFeedLoadStart()
            metrics.feedFirstEvent(start)
            metrics.metadataBatch(index.toLong())
        }
        val report = metrics.report()
        val timingLines = report.lines().count { it.trimStart().startsWith("1s ago") || it.contains(" ms") }
        assertTrue("report must stay short, got $timingLines timing lines", timingLines < 30)
        // The aggregate is still accurate even though only recent samples are kept.
        assertTrue(report.contains("Feed loads: 500"))
    }
}
