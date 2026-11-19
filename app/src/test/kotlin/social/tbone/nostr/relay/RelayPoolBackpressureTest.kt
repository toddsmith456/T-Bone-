package social.tbone.nostr.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Filter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Guards the multiplexer's backpressure contract.
 *
 * Regression under test: the pool emitted into a suspending SharedFlow from inside
 * its per-relay collector loop, so a single busy subscriber stopped *every* relay
 * from delivering. On a slow network that produced "relays report CONNECTED but
 * the feed never fills", because the per-socket channels (which cannot wait) drop.
 *
 * These tests pin the two halves of the fix:
 *  1. a stalled subscriber must not stop messages reaching other subscribers;
 *  2. relay collectors must never block on the multiplexed stream.
 */
class RelayPoolBackpressureTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<MockWebServer>()
    private val pools = mutableListOf<RelayPool>()

    @After
    fun tearDown() {
        scope.cancel()
        servers.forEach { runCatching { it.shutdown() } }
    }

    private fun relayServer(eventsToSend: Int): MockWebServer {
        val server = MockWebServer()
        val burst = AtomicInteger(0)
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                // A realistic relay: it answers every REQ with a burst of events
                // and never waits for the client to go idle.
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.startsWith("[" + '"' + "REQ" + '"') && !text.contains("REQ")) return
                    val run = burst.getAndIncrement()
                    scope.launch {
                        repeat(eventsToSend) { index ->
                            val unique = (run * eventsToSend + index).toString().padStart(64, '0')
                            webSocket.send(
                                """["EVENT","sub",{"id":"$unique","pubkey":"${"a".repeat(64)}","created_at":1,"kind":1,"tags":[],"content":"m$index","sig":"${"b".repeat(128)}"}]""",
                            )
                        }
                    }
                }
            }),
        )
        server.start()
        servers += server
        return server
    }

    private fun poolFor(server: MockWebServer): RelayPool {
        val url = server.url("/").toString().replaceFirst("http", "ws")
        val pool = RelayPool(scope, { RelayConnection(it, OkHttpClient()) })
        pools += pool
        pool.addRelay(url)
        return pool
    }

    /**
     * The regression this pins: with a suspending `emit`, one stalled subscriber
     * filled the 2 048-slot buffer and then blocked the relay collector, so every
     * other subscriber stopped receiving at roughly the buffer size no matter how
     * fast it was. The burst is therefore deliberately larger than the buffer.
     */
    @Test
    fun `a stalled subscriber does not stop delivery to other subscribers`() = runBlocking {
        val eventCount = 4_000
        val pool = poolFor(relayServer(eventCount))

        val fast = CompletableDeferred<Int>()
        val stalled = AtomicInteger(0)

        // Subscriber A: deliberately blocks forever on its first message, exactly
        // like a consumer sitting on a database write or a signature check.
        scope.launch(Dispatchers.IO) {
            pool.messages.collect {
                stalled.incrementAndGet()
                CompletableDeferred<Unit>().await() // never resumes
            }
        }
        delay(100)

        // Subscriber B: the feed. It must still receive the relay's events.
        val fastJob = scope.async {
            val received = AtomicInteger(0)
            withTimeout(20_000) {
                pool.messages.collect {
                    val total = received.incrementAndGet()
                    if (total >= (eventCount * 3) / 4) fast.complete(total)
                }
            }
        }
        pool.subscribe(listOf(Filter(kinds = listOf(1))), id = "sub", label = "test")

        val delivered = withTimeout(20_000) { fast.await() }
        assertTrue(
            "a fast subscriber must not be capped by the buffer: got $delivered of $eventCount",
            delivered >= (eventCount * 3) / 4,
        )
        assertTrue("the stalled subscriber should have started", stalled.get() >= 1)
        fastJob.cancel()
    }

    @Test
    fun `relay collectors never suspend on the multiplexed stream`() = runBlocking {
        val eventCount = 4_000
        val pool = poolFor(relayServer(eventCount))

        // First burst with nobody listening: with a suspending emit this fills the
        // buffer and parks the relay collector, which is the production failure.
        pool.subscribe(listOf(Filter(kinds = listOf(1))), id = "sub", label = "test")
        delay(1_500)

        // A consumer that arrives afterwards must still get traffic. That only
        // happens if the collector stayed alive and its socket was never parked
        // behind a full buffer.
        val received = AtomicInteger(0)
        val job = scope.launch {
            withTimeout(10_000) { pool.messages.collect { received.incrementAndGet() } }
        }
        delay(300)
        pool.subscribe(listOf(Filter(kinds = listOf(1))), id = "sub2", label = "late")

        withTimeout(10_000) {
            while (received.get() == 0) delay(25)
        }
        job.cancel()
        assertTrue("collector stopped delivering; got ${received.get()}", received.get() > 0)
    }

    @Test
    fun `many subscribers and relays do not deadlock the pool`() = runBlocking {
        val pool = poolFor(relayServer(60))
        val connections = 8
        val counters = (1..connections).map { AtomicInteger(0) }
        val jobs = counters.map { counter ->
            scope.async {
                withTimeout(5_000) {
                    runCatching { pool.messages.collect { counter.incrementAndGet() } }
                }
            }
        }
        delay(100)
        pool.subscribe(listOf(Filter(kinds = listOf(1))), id = "fan", label = "fan")
        delay(1_200)
        jobs.forEach { it.cancel() }
        // The pool must still be usable: a further REQ is not blocked.
        val id = pool.subscribe(listOf(Filter(kinds = listOf(1))), id = "after", label = "after")
        assertEquals("after", id)
        assertTrue(
            "at least one subscriber saw traffic: ${counters.map { it.get() }}",
            counters.any { it.get() > 0 },
        )
    }
}
