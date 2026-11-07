package social.tbone.di

import okhttp3.OkHttpClient
import okhttp3.Request
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
}
