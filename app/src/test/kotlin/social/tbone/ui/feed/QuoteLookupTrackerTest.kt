package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for [QuoteLookupTracker].
 *
 * Pins the core Bug #2 fix: resolving any one quote (from local cache, embedded
 * repost, or a fast relay) must NEVER stamp unrelated still-in-flight quotes as
 * "this note isn't available on any relay".
 */
class QuoteLookupTrackerTest {

    @Test
    fun `resolving one quote does not mark still-in-flight siblings unavailable`() {
        val tracker = QuoteLookupTracker()
        assertEquals(listOf("q1", "q2", "q3"), tracker.enqueue(listOf("q1", "q2", "q3")))

        // q1 hits Room cache while q2 and q3 are still queued / in-flight.
        val afterCache = tracker.markResolved(listOf("q1"))
        assertTrue("q2 and q3 must still be loading, not unavailable", afterCache.isEmpty())

        tracker.startSubscription("stage1-a", listOf("q2", "q3"))
        // q2 arrives from relay; q3 is still waiting.
        val afterQ2 = tracker.markResolved(listOf("q2"))
        assertTrue("q3 is still in-flight and must NOT be marked unavailable", afterQ2.isEmpty())
    }

    @Test
    fun `stage-1 handoff with keepPending keeps missing ids loading until stage-2 ends`() {
        val tracker = QuoteLookupTracker()
        tracker.enqueue(listOf("fediverse-note"))
        tracker.startSubscription("stage1", listOf("fediverse-note"))

        // Stage 1 finished on personal relays, handing off to Stage 2 bridge/hint lookup.
        val afterStage1 = tracker.finishSubscription("stage1", keepPending = setOf("fediverse-note"))
        assertTrue("must stay loading during stage-2 fallback", afterStage1.isEmpty())

        tracker.startSubscription("stage2", listOf("fediverse-note"))
        val afterStage2 = tracker.finishSubscription("stage2")
        assertEquals(setOf("fediverse-note"), afterStage2)
    }

    @Test
    fun `late arrival clears terminal unavailable state`() {
        val tracker = QuoteLookupTracker()
        tracker.enqueue(listOf("slow-note"))
        tracker.startSubscription("sub1", listOf("slow-note"))
        assertEquals(setOf("slow-note"), tracker.finishSubscription("sub1"))

        val afterLateArrival = tracker.markResolved(listOf("slow-note"))
        assertTrue(afterLateArrival.isEmpty())
        assertTrue(tracker.isResolved("slow-note"))
    }

    @Test
    fun `overlapping subscriptions do not mark terminal until all covering subs finish`() {
        val tracker = QuoteLookupTracker()
        tracker.enqueue(listOf("shared"))
        tracker.startSubscription("sub-a", listOf("shared"))
        tracker.startSubscription("sub-b", listOf("shared"))

        assertTrue(tracker.finishSubscription("sub-a").isEmpty())
        assertEquals(setOf("shared"), tracker.finishSubscription("sub-b"))
    }

    @Test
    fun `enqueue deduplicates and reset allows fresh retries`() {
        val tracker = QuoteLookupTracker()
        assertEquals(listOf("a", "b"), tracker.enqueue(listOf("a", "b", "a")))
        assertTrue(tracker.enqueue(listOf("a", "b")).isEmpty())

        tracker.startSubscription("s1", listOf("a", "b"))
        tracker.markResolved(listOf("a"))
        assertEquals(setOf("b"), tracker.finishSubscription("s1"))
        assertTrue(tracker.enqueue(listOf("a", "b")).isEmpty())

        // Resetting clears unresolved state so a pull-to-refresh retries "b",
        // while keeping already-resolved "a" unless clearResolved=true.
        tracker.reset(clearResolved = false)
        assertTrue(tracker.snapshotUnresolved().isEmpty())
        assertTrue(tracker.isResolved("a"))
        assertFalse(tracker.isResolved("b"))
        assertEquals(listOf("b"), tracker.enqueue(listOf("a", "b")))
    }

    @Test
    fun `finishSubscription with markTerminal false keeps offline lookups retryable`() {
        val tracker = QuoteLookupTracker()
        assertEquals(listOf("offline-note"), tracker.enqueue(listOf("offline-note")))
        tracker.startSubscription("s-offline", listOf("offline-note"))

        // Zero relays answered (offline / cold-start): must NOT stamp unavailable.
        val unresolved = tracker.finishSubscription("s-offline", markTerminal = false)
        assertTrue("offline lookup must not mark note permanently unavailable", unresolved.isEmpty())
        assertEquals(listOf("offline-note"), tracker.enqueue(listOf("offline-note")))
    }

    @Test
    fun `retry clears terminal unavailable state and re-queues only eligible ids`() {
        val tracker = QuoteLookupTracker()
        tracker.enqueue(listOf("resolved-id", "missing-id", "inflight-id"))
        tracker.markResolved(listOf("resolved-id"))
        tracker.startSubscription("s1", listOf("missing-id"))
        tracker.startSubscription("s2", listOf("inflight-id"))
        assertEquals(setOf("missing-id"), tracker.finishSubscription("s1"))

        val (retriedMissing, afterRetry) = tracker.retry("missing-id")
        assertTrue(retriedMissing)
        assertTrue(afterRetry.isEmpty())

        // Already resolved or currently in-flight IDs are not re-queued.
        assertFalse(tracker.retry("resolved-id").first)
        assertFalse(tracker.retry("inflight-id").first)
        // Already queued (from the retry above) is not double-queued.
        assertFalse(tracker.retry("missing-id").first)
    }
}
