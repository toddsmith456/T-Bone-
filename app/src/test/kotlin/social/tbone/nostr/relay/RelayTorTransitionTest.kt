package social.tbone.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import social.tbone.di.TorCallGuard
import java.util.concurrent.TimeUnit

class RelayTorTransitionTest {
    @Test fun connectedSocketStopsSendingOnModeSwitch() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val client = OkHttpClient()
        val guard = TorCallGuard()
        val connection = RelayConnection(server.url("/").toString().replaceFirst("http://", "ws://"), client)
            .guardedBy(guard, false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connected = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            scope.launch { connection.messages.collect { if (it == RelayMessage.Connected) connected.complete(Unit) } }
            withTimeout(5_000) { connected.await() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            guard.update(true)
            assertFalse(connection.send(ClientMessage.Close("private-subscription")))
        } finally {
            scope.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun staleHandshakeIsRejectedEvenAfterModeCyclesBack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val client = OkHttpClient()
        val guard = TorCallGuard()
        val connection = RelayConnection(server.url("/").toString().replaceFirst("http://", "ws://"), client)
            .guardedBy(guard, false)
        try {
            guard.update(true)
            guard.update(false)
            withTimeout(5_000) { connection.messages.first() }
            fail("stale connection should not open")
        } catch (_: NoSuchElementException) {
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    @Test fun inFlightHandshakeIsCancelledOnModeSwitch() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val client = OkHttpClient()
        val guard = TorCallGuard()
        val connection = RelayConnection(server.url("/").toString().replaceFirst("http://", "ws://"), client)
            .guardedBy(guard, false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
        try {
            scope.launch { try { connection.messages.collect {} } finally { completed.complete(Unit) } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            guard.update(true)
            withTimeout(5_000) { completed.await() }
        } finally {
            scope.cancel()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }
}
