package social.tbone.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class TorCallGuardTest {
    private fun call() = OkHttpClient().newCall(Request.Builder().url("https://example.org/").build())

    @Test fun modeSwitchCancelsActiveCallsAndRejectsStaleRegistrations() {
        val guard = TorCallGuard()
        val direct = call()
        guard.register(direct, false)
        assertFalse(direct.isCanceled())
        guard.update(true)
        assertTrue(direct.isCanceled())
        val stale = call()
        try {
            guard.register(stale, false)
            fail("stale direct call accepted")
        } catch (_: IOException) { }
        assertTrue(stale.isCanceled())
        val proxied = call()
        guard.register(proxied, true)
        guard.unregister(proxied)
        guard.update(false)
        assertFalse(proxied.isCanceled())
    }

    @Test fun sameModeDoesNotCancelCalls() {
        val guard = TorCallGuard()
        val call = call()
        guard.register(call, false)
        guard.update(false)
        assertFalse(call.isCanceled())
    }

    @Test fun cancelledOldModeCallCannotReachServerAfterTransition() {
        val server = MockWebServer()
        server.start()
        try {
            val guard = TorCallGuard()
            val request = Request.Builder().url(server.url("/followers")).build()
            val call = OkHttpClient().newCall(request)
            guard.register(call, false)
            guard.update(true)
            try {
                call.execute().close()
                fail("cancelled direct call unexpectedly completed")
            } catch (_: IOException) { }
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { server.shutdown() }
    }

    @Test fun modeSwitchInterruptsAnAlreadyRunningRequest() {
        val server = MockWebServer()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        try {
            val guard = TorCallGuard()
            val call = client.newCall(Request.Builder().url(server.url("/held")).build())
            guard.register(call, false)
            val result = executor.submit<Boolean> {
                try { call.execute().close(); false } catch (_: IOException) { true }
                finally { guard.unregister(call) }
            }
            assertNotNull("request should reach server before transition", server.takeRequest(5, TimeUnit.SECONDS))
            guard.update(true)
            assertTrue("old-mode running call must fail", result.get(5, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            executor.shutdownNow()
            server.shutdown()
        }
    }

}
