package social.tbone.di

import okhttp3.Call
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Coordinates the mode used by selected HTTP calls with Tor settings changes.
 * A call is registered before it can start; changing mode cancels all calls
 * started under the old mode, and stale callers may not register new ones.
 */
internal class TorCallGuard {
    private var torEnabled = false
    private val calls = Collections.newSetFromMap(IdentityHashMap<Call, Boolean>())

    @Synchronized fun update(enabled: Boolean) {
        if (enabled == torEnabled) return
        torEnabled = enabled
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized fun register(call: Call, expectedTor: Boolean) {
        if (expectedTor != torEnabled) {
            call.cancel()
            throw IOException("Tor setting changed; request cancelled")
        }
        calls.add(call)
    }

    @Synchronized fun unregister(call: Call) { calls.remove(call) }
}

/** Shared by the settings collector and the requests which opt into this guard. */
internal object TorCallPolicy {
    val guard = TorCallGuard()
}
