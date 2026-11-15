package social.tbone.nostr.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Filter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Guards the workaround for OkHttp 4.12.0's RealWebSocket concurrency bug
 * (square/okhttp#6719, #6723): disabling permessage-deflate ensures neither
 * MessageDeflater nor MessageInflater is ever allocated or closed across
 * Dispatcher and TaskRunner threads.
 */
class RelayWebSocketDeflateTest {

    @Test
    fun buildClientDisablesWebSocketCompressionForDirectAndTor() {
        val direct = RelayConnection.buildClient(useTor = false)
        val tor = RelayConnection.buildClient(useTor = true)

        assertEquals(Long.MAX_VALUE, direct.minWebSocketMessageToCompress)
        assertEquals(Long.MAX_VALUE, tor.minWebSocketMessageToCompress)
        assertTrue(RelayConnection.DISABLE_WEBSOCKET_DEFLATE_INTERCEPTOR in direct.interceptors)
        assertTrue(RelayConnection.DISABLE_WEBSOCKET_DEFLATE_INTERCEPTOR in tor.interceptors)
    }

    @Test
    fun relayWebSocketUpgradeStripsPerMessageDeflateEvenWithCustomClient() = runBlocking {
        val server = MockWebServer()
        val receivedText = CompletableDeferred<String>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    receivedText.complete(text)
                }
            }),
        )
        server.start()

        // Intentionally pass a bare OkHttpClient (default 1024-byte deflate threshold)
        // to confirm RelayConnection enforces the guard on every socket.
        val rawClient = OkHttpClient()
        val wsUrl = server.url("/").toString().replaceFirst("http://", "ws://")
        val connection = RelayConnection(wsUrl, rawClient)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connected = CompletableDeferred<Unit>()
        try {
            scope.launch {
                connection.messages.collect { msg ->
                    if (msg == RelayMessage.Connected) connected.complete(Unit)
                }
            }
            withTimeout(5_000) { connected.await() }

            val upgradeReq = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull(upgradeReq)
            assertNull(
                "Sec-WebSocket-Extensions must be stripped to prevent permessage-deflate race",
                upgradeReq!!.getHeader("Sec-WebSocket-Extensions"),
            )

            // Send a >4 KiB batched REQ message (well above OkHttp's 1024-byte default threshold)
            // and verify it arrives intact without permessage-deflate.
            val manyAuthors = List(80) { i -> "%064x".format(i) }
            val req = ClientMessage.Req("big-batch", listOf(Filter(authors = manyAuthors, kinds = listOf(1))))
            assertTrue(req.toJson().length > 4096)
            assertTrue(connection.send(req))

            val delivered = withTimeout(5_000) { receivedText.await() }
            assertEquals(req.toJson(), delivered)
        } finally {
            scope.cancel()
            rawClient.dispatcher.cancelAll()
            rawClient.connectionPool.evictAll()
            rawClient.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test
    fun concurrentLargeSendsAndCancellationsDoNotThrowUncaughtExceptions() = runBlocking {
        val uncaught = AtomicReference<Throwable?>(null)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.compareAndSet(null, e) }

        val server = MockWebServer()
        val iterations = 12
        repeat(iterations) {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        }
        server.start()

        val client = RelayConnection.buildClient(useTor = false)
        val wsUrl = server.url("/").toString().replaceFirst("http://", "ws://")
        val largeAuthors = List(100) { i -> "%064x".format(i) }
        val largeReq = ClientMessage.Req("stress", listOf(Filter(authors = largeAuthors, kinds = listOf(1))))

        try {
            repeat(iterations) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val connected = CompletableDeferred<Unit>()
                val connection = RelayConnection(wsUrl, client)
                scope.launch {
                    connection.messages.collect { if (it == RelayMessage.Connected) connected.complete(Unit) }
                }
                withTimeout(5_000) { connected.await() }
                repeat(5) { connection.send(largeReq) }
                // Cancel mid-flight while WriterTask is draining the >6 KiB frames.
                scope.cancel()
            }
            // Give OkHttp's Dispatcher/TaskRunner threads a brief window to finish teardown.
            kotlinx.coroutines.delay(250)
            assertNull("Unexpected uncaught exception during socket teardown", uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            server.shutdown()
        }
    }

    @Test
    fun nonWebSocketHttpRequestsPassThroughInterceptorUntouched() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()
        try {
            val client = RelayConnection.buildClient(useTor = false)
            val request = Request.Builder()
                .url(server.url("/upload"))
                .header("X-Test", "preserved")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("ok", response.body?.string())
            }
            val recorded = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("preserved", recorded.getHeader("X-Test"))
        } finally {
            server.shutdown()
        }
    }
}
