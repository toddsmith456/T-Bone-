package social.tbone.di

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
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
}
