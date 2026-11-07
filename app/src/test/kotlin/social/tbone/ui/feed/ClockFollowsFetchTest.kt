package social.tbone.ui.feed

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayMessage

@OptIn(ExperimentalCoroutinesApi::class)
class ClockFollowsFetchTest {
    private fun event(id: String, timestamp: Long, author: String = "author", kind: Int = EventKind.FOLLOW_LIST) =
        Event(id, author, timestamp, kind, emptyList(), "", "")
    private fun msg(id: String, event: Event) = PoolMessage("wss://relay", RelayMessage.EventMessage(id, event))

    @Test fun immediateResponseIsCollectedAndNewerWinsDuringGrace() = runTest {
        val flow = MutableSharedFlow<PoolMessage>(extraBufferCapacity = 20)
        var closed = 0
        val result = async { fetchClockFollowEvent(flow, "sub", "author", {
            assertTrue(flow.tryEmit(msg("sub", event("z", 2))))
        }, { closed++ }, timeoutMs = 8_000, graceMs = 2_000, verify = { it.id != "bad" }) }
        runCurrent()
        flow.emit(msg("other", event("wrong-sub", 20)))
        flow.emit(msg("sub", event("wrong-author", 20, author = "other")))
        flow.emit(msg("sub", event("bad", 20)))
        flow.emit(msg("sub", event("a", 2))) // deterministic tie-break
        flow.emit(msg("sub", event("new", 3)))
        runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals("new", result.await()?.id)
        assertEquals(1, closed)
    }

    @Test fun noResultTimesOutAndCancellationAlwaysCloses() = runTest {
        val flow = MutableSharedFlow<PoolMessage>()
        var closed = 0
        val timed = async { fetchClockFollowEvent(flow, "sub", "author", {}, { closed++ }, timeoutMs = 100, verify = { true }) }
        runCurrent(); advanceTimeBy(101); runCurrent()
        assertNull(timed.await()); assertEquals(1, closed)
        val cancelled = async { fetchClockFollowEvent(flow, "sub", "author", {}, { closed++ }, verify = { true }) }
        runCurrent(); cancelled.cancelAndJoin(); assertEquals(2, closed)
    }

    @Test fun subscribeFailureStillCloses() = runTest {
        var closed = 0
        try {
            fetchClockFollowEvent(MutableSharedFlow(), "sub", "author", { error("failed") }, { closed++ }, verify = { true })
            fail("expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, closed)
    }
}
