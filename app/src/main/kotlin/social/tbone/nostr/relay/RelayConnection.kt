package social.tbone.nostr.relay

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val NORMAL_CLOSURE = 1000

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

    val messages: Flow<RelayMessage> = callbackFlow {
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Timber.d("Connected: $url")
                webSocket.set(ws)
                trySend(RelayMessage.Connected)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                trySend(RelayMessage.parse(text))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Timber.w("Failure on $url: ${t.message}")
                close(t)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Timber.d("Closed: $url — $reason")
                webSocket.set(null)
                close()
            }
        }

        val ws = client.newWebSocket(request, listener)

        awaitClose {
            ws.close(NORMAL_CLOSURE, "collector cancelled")
            webSocket.set(null)
        }
    }.catch { t ->
        Timber.e(t, "Flow error on $url")
    }

    /**
     * Sends a message to the relay. Returns false if the socket is not open.
     */
    fun send(message: ClientMessage): Boolean {
        val json = message.toJson()
        Timber.d("→ $url [${message::class.simpleName}]")
        return webSocket.get()?.send(json) ?: false
    }

    companion object {
        fun defaultClient(): OkHttpClient = buildClient(useTor = false)

        /**
         * @param proxyType HTTP (preferred — no DNS leak) or SOCKS (fallback).
         * @param torPort   Port confirmed reachable by OrbotHelper.
         */
        fun buildClient(
            useTor: Boolean,
            proxyType: java.net.Proxy.Type = java.net.Proxy.Type.HTTP,
            torPort: Int = social.tbone.Tunables.TOR_HTTP_PROXY_PORT,
        ): OkHttpClient =
            OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // WebSocket — no read timeout
                .apply {
                    if (useTor) {
                        proxy(java.net.Proxy(
                            proxyType,
                            java.net.InetSocketAddress("127.0.0.1", torPort),
                        ))
                    }
                }
                .build()
    }
}
