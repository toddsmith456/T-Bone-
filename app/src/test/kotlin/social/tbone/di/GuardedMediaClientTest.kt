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
