package social.tbone.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuardedMediaClientTest {
    @Test fun failClosedStartupClientIsReplacedWhenSavedModeIsTorOff() {
        val guard = TorCallGuard(initialTor = true)
        val startupClient = guardedMediaClient(OkHttpClient(), true, guard)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("image bytes"))
        server.start()
        var directClient: OkHttpClient? = null
        try {
            // Settings loading changes the guard to the saved Tor-off mode. The
            // bootstrap client must stay rejected; the first configured client
            // must be able to fetch the image without waiting for another setting
            // change.
            guard.update(false)
            try {
                startupClient.newCall(Request.Builder().url(server.url("/stale-image")).build())
                    .execute().use { fail("bootstrap Tor client was accepted after Tor was disabled") }
            } catch (_: IOException) { }
            assertNull("stale client must not reach the server", server.takeRequest(200, TimeUnit.MILLISECONDS))

            // Build the replacement only after the guard publishes the saved mode,
            // just as ImageClientProvider.update() does in the startup collector.
            val configuredClient = guardedMediaClient(OkHttpClient(), false, guard)
            directClient = configuredClient
            configuredClient.newCall(Request.Builder().url(server.url("/image")).build()).execute().use {
                assertEquals("image bytes", it.body!!.string())
            }
            assertNotNull("configured media client should reach the server", server.takeRequest(5, TimeUnit.SECONDS))
        } finally {
            startupClient.dispatcher.executorService.shutdownNow()
            startupClient.connectionPool.evictAll()
            directClient?.dispatcher?.executorService?.shutdownNow()
            directClient?.connectionPool?.evictAll()
            server.shutdown()
        }
    }

    @Test fun staleClientCannotRestartAfterAnOffOnOffCycle() {
        val guard = TorCallGuard()
        val client = guardedMediaClient(OkHttpClient(), false, guard)
        val server = MockWebServer()
        server.start()
        try {
            guard.update(true)
            guard.update(false)
            try {
                client.newCall(Request.Builder().url(server.url("/stale")).build()).execute().close()
                fail("old media client was accepted after a mode cycle")
            } catch (_: IOException) { }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }

    @Test fun modeChangeCancelsMediaRequestWaitingForHeaders() {
        val guard = TorCallGuard()
        val client = guardedMediaClient(OkHttpClient(), false, guard)
        val server = MockWebServer()
        val executor = Executors.newSingleThreadExecutor()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val call = client.newCall(Request.Builder().url(server.url("/image")).build())
            val result = executor.submit<Boolean> {
                try { call.execute().close(); false } catch (_: IOException) { true }
            }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            guard.update(true)
            assertTrue(result.get(5, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
        } finally {
            executor.shutdownNow()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test fun responseBodyRemainsTrackedUntilClosed() {
        val guard = TorCallGuard()
        val client = guardedMediaClient(OkHttpClient(), false, guard)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("private media").setBodyDelay(2, TimeUnit.SECONDS))
        server.start()
        try {
            val call = client.newCall(Request.Builder().url(server.url("/stream")).build())
            val response = call.execute()
            guard.update(true)
            assertTrue("headers alone must not end tracking", call.isCanceled())
            try {
                response.use { it.body!!.string() }
                fail("cancelled response body unexpectedly completed")
            } catch (_: IOException) { }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test fun closedResponseIsNoLongerTracked() {
        val guard = TorCallGuard()
        val client = guardedMediaClient(OkHttpClient(), false, guard)
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("ok"))
        server.start()
        try {
            val call = client.newCall(Request.Builder().url(server.url("/done")).build())
            call.execute().use { assertEquals("ok", it.body!!.string()) }
            guard.update(true)
            assertFalse(call.isCanceled())
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
