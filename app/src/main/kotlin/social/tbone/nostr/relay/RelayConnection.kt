package social.tbone.nostr.relay

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Interceptor
import social.tbone.di.TorCallGuard
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A single WebSocket connection to one Nostr relay.
 *
 * [messages] is a cold Flow — a new WebSocket is opened each time it is collected
 * and closed when the collector cancels. Use [RelayPool] to manage multiple connections.
 *
 * Outbound messages are sent via [send]. The connection must be collected (open)
 * before sending will succeed.
 */
class RelayConnection(
    val url: String,
    private val client: OkHttpClient,
) {
    // AtomicReference ensures the WebSocket reference is visible across threads:
    // the OkHttp callback thread writes it; coroutines calling send() read it.
    private val webSocket = AtomicReference<WebSocket?>(null)
    private data class TransportGuard(val guard: TorCallGuard, val tor: Boolean, val epoch: Long)
    private var transportGuard: TransportGuard? = null

    /** Set before this connection is published or collected. */
    internal fun guardedBy(guard: TorCallGuard, tor: Boolean): RelayConnection = apply {
        transportGuard = TransportGuard(guard, tor, guard.snapshot(tor))
    }

    val messages: Flow<RelayMessage> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val policy = transportGuard
        val handshake = AtomicReference<Call?>(null)
        val connectClient = if (policy == null) client else client.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val call = chain.call()
                handshake.set(call)
                policy.guard.registerAtEpoch(call, policy.tor, policy.epoch)
                try { chain.proceed(chain.request()) }
                catch (error: Throwable) { policy.guard.unregister(call); throw error }
            }).build()
        fun forgetHandshake() { handshake.getAndSet(null)?.let { policy?.guard?.unregister(it) } }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (policy != null && !policy.guard.registerSocket(ws, policy.tor, policy.epoch)) {
                    forgetHandshake()
                    close()
                    return
                }
                forgetHandshake()
                Timber.d("Connected: $url")
                webSocket.set(ws)
                trySend(RelayMessage.Connected)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (policy != null && !policy.guard.isCurrent(policy.tor, policy.epoch)) return
                trySend(RelayMessage.parse(text))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                forgetHandshake()
                policy?.guard?.unregisterSocket(ws)
                webSocket.compareAndSet(ws, null)
                Timber.w("Failure on $url: ${t.message}")
                close(t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                forgetHandshake()
                policy?.guard?.unregisterSocket(ws)
                Timber.d("Closed: $url — $reason")
                webSocket.compareAndSet(ws, null)
                close()
            }
        }

        val ws = connectClient.newWebSocket(request, listener)

        awaitClose {
            // Abort rather than flush queued messages after a mode change.
            ws.cancel()
            forgetHandshake()
            policy?.guard?.unregisterSocket(ws)
            webSocket.compareAndSet(ws, null)
        }
    }.catch { t ->
        Timber.e(t, "Flow error on $url")
    }

    /**
     * Sends a message to the relay. Returns false if the socket is not open.
     */
    fun send(message: ClientMessage): Boolean {
        val json = message.toJson()
        Timber.d("→ ${url.substringAfter("://").substringBefore("/")} [${message::class.simpleName}]")
        val socket = webSocket.get() ?: return false
        val policy = transportGuard
        return if (policy == null) socket.send(json)
        else policy.guard.sendIfCurrent(socket, json, policy.tor, policy.epoch)
    }

    companion object {
        /** Citrine loopback relays must connect directly, never through Orbot. */
        fun isLocalRelayUrl(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }
                .getOrNull()
                ?.trim('[', ']')
                ?.lowercase()
                ?: return false
            return host == "localhost" || host == "::1" || host.startsWith("127.")
        }

        fun defaultClient(): OkHttpClient = buildClient(useTor = false)

        /**
         * @param proxyType HTTP (preferred — no DNS leak) or SOCKS (fallback).
         * @param torPort   Port confirmed reachable by OrbotHelper.
         */
        fun buildClient(
            useTor: Boolean,
            proxyType: java.net.Proxy.Type = java.net.Proxy.Type.HTTP,
            torPort: Int = social.tbone.Tunables.TOR_HTTP_PROXY_PORT,
            bypassProxy: Boolean = false,
        ): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // WebSocket — no read timeout
                .apply {
                    if (useTor && !bypassProxy) {
                        proxy(java.net.Proxy(
                            proxyType,
                            java.net.InetSocketAddress("127.0.0.1", torPort),
                        ))
                    }
                }
                .build()
    }
}
