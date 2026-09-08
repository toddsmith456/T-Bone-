package social.tbone.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.Tunables
import social.tbone.nostr.Filter
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Manages a set of [RelayConnection]s and multiplexes their messages into a
 * single [messages] flow.
 *
 * Subscriptions opened via [subscribe] are automatically re-sent to a relay
 * when it reconnects. Call [unsubscribe] or cancel the returned [Job] to stop.
 */
class RelayPool(
    private val scope: CoroutineScope,
    private var connectionFactory: (url: String) -> RelayConnection,
) {
    private val connections = ConcurrentHashMap<String, RelayEntry>()
    private val activeSubscriptions = ConcurrentHashMap<String, Subscription>()

    private val _messages = MutableSharedFlow<PoolMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<PoolMessage> = _messages.asSharedFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayStatus>> = _relayStatuses.asStateFlow()

    // ── Diagnostics ─────────────────────────────────────────────────────────────
    // Counts EVENT/EOSE messages multiplexed out of the pool, dumped every 5s with
    // the active-subscription breakdown, so subscription leaks and event floods are
    // visible in logcat without a profiler.
    private val eventCounter = AtomicLong(0)
    private val eoseCounter = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(STATS_INTERVAL_MS)
                val events = eventCounter.getAndSet(0)
                val eose = eoseCounter.getAndSet(0)
                if (events == 0L && activeSubscriptions.isEmpty()) continue
                val byLabel = activeSubscriptions.values
                    .groupingBy { it.label }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}=${it.value}" }
                Timber.d(
                    "PoolStats: ${events / (STATS_INTERVAL_MS / 1000)} evt/s, " +
                        "${eose / (STATS_INTERVAL_MS / 1000)} eose/s, " +
                        "activeSubs=${activeSubscriptions.size} [$byLabel], " +
                        "relays=${connections.size}",
                )
            }
        }
    }

    // ── Relay management ──────────────────────────────────────────────────────

    fun addRelay(url: String) {
        val normalized = normalize(url)
        if (connections.containsKey(normalized)) return
        val connection = connectionFactory(normalized)
        val job = scope.launch { connectWithRetry(normalized, connection) }
        connections[normalized] = RelayEntry(connection, job)
        _relayStatuses.update { it + (normalized to RelayStatus.CONNECTING) }
        Timber.d("Added relay: $normalized")
    }

    fun removeRelay(url: String) {
        val normalized = normalize(url)
        connections.remove(normalized)?.job?.cancel()
        _relayStatuses.update { it - normalized }
        Timber.d("Removed relay: $normalized")
    }

    private val relayRefCounts = ConcurrentHashMap<String, Int>()

    /**
     * Connect to a relay with reference counting: every [acquireRelay] must be
     * matched by a [releaseRelay], and the connection is only torn down when the
     * last user releases it. Several screens (search, composer @-mentions) may
     * hold the same relay at once without yanking it out from under each other.
     */
    fun acquireRelay(url: String) {
        val normalized = normalize(url)
        synchronized(relayRefCounts) {
            relayRefCounts[normalized] = (relayRefCounts[normalized] ?: 0) + 1
        }
        addRelay(normalized)
    }

    fun releaseRelay(url: String) {
        val normalized = normalize(url)
        val remaining = synchronized(relayRefCounts) {
            val count = (relayRefCounts[normalized] ?: 0) - 1
            if (count <= 0) relayRefCounts.remove(normalized) else relayRefCounts[normalized] = count
            count
        }
        if (remaining <= 0) removeRelay(normalized)
    }

    /**
     * Swap the transport (e.g. plain vs. Tor SOCKS proxy) and reconnect all
     * existing relays so the new client takes effect immediately.
     */
    fun updateTransport(client: okhttp3.OkHttpClient) {
        connectionFactory = { url -> RelayConnection(url, client) }
        val urls = connections.keys.toList()
        urls.forEach { removeRelay(it) }
        urls.forEach { addRelay(it) }
        Timber.d("Transport updated, reconnecting ${urls.size} relay(s)")
    }

    fun relayUrls(): Set<String> = connections.keys.toSet()

    companion object {
        /** Strip trailing slashes so wss://foo and wss://foo/ are treated as the same relay. */
        fun normalize(url: String): String = url.trimEnd('/')

        private const val STATS_INTERVAL_MS = 5_000L
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    /**
     * Opens a subscription on all current (and future) relays.
     * Returns the subscription ID so callers can filter [messages] by it.
     */
    fun subscribe(filters: List<Filter>, id: String = newSubId(), label: String = "sub"): String =
        subscribeTo(filters, id, label, relays = null)

    /**
     * Opens a subscription scoped to [relays] only — used by location channels
     * so each channel talks only to its own handful of relays instead of every
     * connected relay. Returns the subscription ID.
     */
    fun subscribeTo(
        filters: List<Filter>,
        id: String = newSubId(),
        label: String = "sub",
        relays: Set<String>?,
    ): String {
        val sub = Subscription(id, filters, label, relays?.map { normalize(it) }?.toSet())
        activeSubscriptions[id] = sub
        val req = ClientMessage.Req(id, filters)
        if (sub.relays != null) {
            sub.relays.forEach { url -> connections[url]?.connection?.send(req) }
        } else {
            connections.values.forEach { it.connection.send(req) }
        }
        Timber.d("Subscribed: $id [$label] relays=${sub.relays?.size ?: "all"} active=${activeSubscriptions.size}")
        return id
    }

    /** Broadcasts an event to all connected relays. */
    fun publish(event: social.tbone.nostr.Event) {
        val msg = ClientMessage.Publish(event)
        connections.values.forEach { it.connection.send(msg) }
        Timber.d("Published event ${event.id.take(8)}… to ${connections.size} relay(s)")
    }

    /** Publishes an event to a specific set of relays (location channels). */
    fun publishTo(event: social.tbone.nostr.Event, urls: Set<String>) {
        val msg = ClientMessage.Publish(event)
        var sent = 0
        urls.forEach { url ->
            if (connections[normalize(url)]?.connection?.send(msg) == true) sent++
        }
        Timber.d("Published event ${event.id.take(8)}… to $sent/${urls.size} relay(s)")
    }

    /** Sends a message to a specific relay by URL. Returns false if not connected. */
    fun send(relayUrl: String, message: ClientMessage): Boolean =
        connections[relayUrl]?.connection?.send(message) ?: false

    fun unsubscribe(subscriptionId: String) {
        val sub = activeSubscriptions.remove(subscriptionId) ?: return
        val close = ClientMessage.Close(subscriptionId)
        connections.values.forEach { it.connection.send(close) }
        Timber.d("Unsubscribed: $subscriptionId [${sub.label}] active=${activeSubscriptions.size}")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun connectWithRetry(url: String, connection: RelayConnection) {
        var delayMs = Tunables.RECONNECT_DELAY_MS
        while (true) {
            Timber.d("Connecting: $url")
            try {
                connection.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Connected -> {
                            delayMs = Tunables.RECONNECT_DELAY_MS
                            _relayStatuses.update { it + (url to RelayStatus.CONNECTED) }
                            activeSubscriptions.values.forEach { sub ->
                                // A scoped subscription is only sent to its own relays.
                                if (sub.relays == null || normalize(url) in sub.relays) {
                                    connection.send(ClientMessage.Req(sub.id, sub.filters))
                                }
                            }
                            Timber.d("Replayed ${activeSubscriptions.size} subscription(s) to $url")
                        }
                        else -> {
                            when (message) {
                                is RelayMessage.EventMessage -> eventCounter.incrementAndGet()
                                is RelayMessage.EndOfStoredEvents -> eoseCounter.incrementAndGet()
                                else -> Unit
                            }
                            delayMs = Tunables.RECONNECT_DELAY_MS
                            _messages.emit(PoolMessage(url, message))
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Collection error on $url")
            }

            if (!connections.containsKey(url)) break

            _relayStatuses.update { it + (url to RelayStatus.DISCONNECTED) }
            Timber.d("Reconnecting $url in ${delayMs}ms")
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(Tunables.MAX_RECONNECT_DELAY_MS)
        }
    }

    private fun newSubId(): String = UUID.randomUUID().toString().take(8)

    // ── Data classes ──────────────────────────────────────────────────────────

    private data class RelayEntry(val connection: RelayConnection, val job: Job)
    private data class Subscription(
        val id: String,
        val filters: List<Filter>,
        val label: String,
        /** When set, this subscription only talks to these relays (null = all). */
        val relays: Set<String>? = null,
    )
}

/**
 * A relay message tagged with the relay URL it arrived from.
 */
data class PoolMessage(
    val relayUrl: String,
    val message: RelayMessage,
)
