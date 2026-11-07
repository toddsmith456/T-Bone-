package social.tbone.di

import okhttp3.Call
import okhttp3.WebSocket
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Coordinates the mode used by selected HTTP calls with Tor settings changes.
 * A call is registered before it can start; changing mode cancels all calls
 * started under the old mode, and stale callers may not register new ones.
 */
internal class TorCallGuard(initialTor: Boolean = false) {
    private var torEnabled = initialTor
    private var epoch = 0L
    private val calls = Collections.newSetFromMap(IdentityHashMap<Call, Boolean>())
    private val sockets = Collections.newSetFromMap(IdentityHashMap<WebSocket, Boolean>())

    @Synchronized fun update(enabled: Boolean) {
        if (enabled == torEnabled) return
        torEnabled = enabled
        epoch++
        calls.forEach { it.cancel() }
        calls.clear()
        sockets.forEach { it.cancel() }
        sockets.clear()
    }

    @Synchronized fun register(call: Call, expectedTor: Boolean) {
        if (expectedTor != torEnabled) {
            call.cancel()
            throw IOException("Tor setting changed; request cancelled")
        }
        calls.add(call)
    }

    /** Bind reusable clients to the settings generation they were created for. */
    @Synchronized fun snapshot(expectedTor: Boolean): Long =
        if (expectedTor == torEnabled) epoch else Long.MIN_VALUE

    @Synchronized fun registerAtEpoch(call: Call, expectedTor: Boolean, expectedEpoch: Long) {
        if (expectedTor != torEnabled || expectedEpoch != epoch) {
            call.cancel()
            throw IOException("Tor setting changed; request cancelled")
        }
        calls.add(call)
    }

    @Synchronized fun unregister(call: Call) { calls.remove(call) }

    @Synchronized fun registerSocket(socket: WebSocket, expectedTor: Boolean, expectedEpoch: Long): Boolean {
        if (expectedTor != torEnabled || expectedEpoch != epoch) {
            socket.cancel()
            return false
        }
        sockets.add(socket)
        return true
    }

    @Synchronized fun unregisterSocket(socket: WebSocket) { sockets.remove(socket) }

    @Synchronized fun sendIfCurrent(socket: WebSocket, message: String, expectedTor: Boolean, expectedEpoch: Long): Boolean =
        expectedTor == torEnabled && expectedEpoch == epoch && socket in sockets && socket.send(message)

    @Synchronized fun isCurrent(expectedTor: Boolean, expectedEpoch: Long): Boolean =
        expectedTor == torEnabled && expectedEpoch == epoch
}

/** Shared by the settings collector and the requests which opt into this guard. */
internal object TorCallPolicy {
    // Unknown persisted settings are treated as proxy-only at startup.
    val guard = TorCallGuard(initialTor = true)
}
