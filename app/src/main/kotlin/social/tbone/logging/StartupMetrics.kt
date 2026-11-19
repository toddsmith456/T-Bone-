package social.tbone.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-lifetime startup and network timing counters.
 *
 * The cold-start investigation behind this had to reason about a slow device from
 * source alone: there was no way to tell whether the feed was waiting on the
 * network, on the database, or on a stalled message pipe. These counters answer
 * that in one screen, on a real phone, without a profiler attached.
 *
 * Deliberately cheap and bounded:
 *  - `AtomicLong` counters only, no per-event allocation on hot paths;
 *  - fixed-size ring buffers for the interesting durations;
 *  - nothing is persisted and nothing leaves the device unless the user chooses
 *    to share it. See `docs/privacy-diagnostics.md`.
 */
@Singleton
class StartupMetrics @Inject constructor() {


    private data class Sample(val label: String, val millis: Long, val atMillis: Long)

    private val lock = Any()
    private val feedSamples = ArrayDeque<Sample>()
    private val metadataSamples = ArrayDeque<Sample>()

    private val _marked = MutableStateFlow(0L)
    /** Emits a nonce each time a sample is recorded, so a UI can poll cheaply. */
    val revision: StateFlow<Long> = _marked.asStateFlow()

    private val launches = AtomicLong(0)
    private val feedLoads = AtomicLong(0)
    private val feedSucceeded = AtomicLong(0)
    private val feedTimedOut = AtomicLong(0)

    // Populated by RelayPool: how much traffic the multiplexer actually carries,
    // and how much a slow subscriber caused to be displaced.
    @Volatile private var relayDropped = 0L
    @Volatile private var relayEvents = 0L
    @Volatile private var activeSubscriptions = 0
    @Volatile private var connectedRelays = 0

    private val verifyChecks = AtomicLong(0)
    private val verifySkipped = AtomicLong(0)

    // ── Feed cold start ───────────────────────────────────────────────────────

    /** Call once per process, as early as possible in Application.onCreate. */
    fun onProcessStart() {
        launches.incrementAndGet()
    }

    /** Call when a feed load begins; returns a token to pass to [feedFirstEvent]. */
    fun onFeedLoadStart(): Long {
        feedLoads.incrementAndGet()
        return System.currentTimeMillis()
    }

    /** Time from load start to the first rendered prompt. */
    fun feedFirstEvent(startedAtMillis: Long) {
        feedSucceeded.incrementAndGet()
        record(feedSamples, "first event", System.currentTimeMillis() - startedAtMillis)
    }

    /** A load that ended because the watchdog fired, not because data arrived. */
    fun feedTimedOut(startedAtMillis: Long) {
        feedTimedOut.incrementAndGet()
        record(feedSamples, "timed out", System.currentTimeMillis() - startedAtMillis)
    }

    /** Time from a metadata batch REQ to EOSE. */
    fun metadataBatch(elapsedMillis: Long) = record(metadataSamples, "metadata EOSE", elapsedMillis)

    // ── Relay multiplexer ─────────────────────────────────────────────────────

    fun relaySnapshot(events: Long, dropped: Long, subscriptions: Int, relays: Int) {
        relayEvents = events
        relayDropped = dropped
        activeSubscriptions = subscriptions
        connectedRelays = relays
    }

    // ── Verification ──────────────────────────────────────────────────────────

    fun verification(cheap: Boolean) {
        if (cheap) verifySkipped.incrementAndGet() else verifyChecks.incrementAndGet()
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    private fun record(into: ArrayDeque<Sample>, label: String, millis: Long) {
        if (millis < 0) return
        synchronized(lock) {
            into.addLast(Sample(label, millis, System.currentTimeMillis()))
            while (into.size > MAX_SAMPLES) into.removeFirst()
        }
        _marked.value = System.currentTimeMillis()
    }

    /**
     * Human-readable performance block.
     *
     * Contains only timings and counts: no note content, no pubkeys, no relay
     * URLs, no file paths. That is the point — it is the diagnostics a bug report
     * usually needs, without the traffic data.
     */
    fun report(): String = buildString {
        appendLine("=== PERFORMANCE (this session) ===")
        appendLine("Process launches: $launches")
        appendLine(
            "Feed loads: $feedLoads · first-event: $feedSucceeded · timed out: $feedTimedOut",
        )
        appendLine(
            "Relay multiplexer: $connectedRelays relay(s), $activeSubscriptions active sub(s), " +
                "$relayEvents event(s) seen, $relayDropped displaced by backpressure",
        )
        appendLine("Verification: $verifyChecks full · $verifySkipped cache hits")

        val feed = synchronized(lock) { feedSamples.toList() }
        if (feed.isEmpty()) {
            appendLine("Feed timings: no completed load yet")
        } else {
            appendLine("Feed timings (${feed.size}, newest last):")
            feed.takeLast(MAX_REPORTED).forEach { sample ->
                appendLine("  ${formatAge(sample.atMillis)} · ${sample.label}: ${sample.millis} ms")
            }
        }

        val metadata = synchronized(lock) { metadataSamples.toList() }
        if (metadata.isNotEmpty()) {
            val sorted = metadata.map { it.millis }.sorted()
            appendLine(
                "Metadata EOSE: n=${sorted.size} · median ${sorted[sorted.size / 2]} ms · " +
                    "worst ${sorted.last()} ms",
            )
        }
        append(
            "Verdict: " + verdict(),
        )
    }

    /**
     * A one-line reading of the numbers, so a non-engineer can paste the report
     * and still get told what it means.
     */
    private fun verdict(): String {
        val feed = synchronized(lock) { feedSamples.toList() }
        val worst = feed.maxOfOrNull { it.millis } ?: return "not enough data yet"
        val displaced = relayDropped
        return when {
            feedTimedOut.get() > 0L && displaced > 0L ->
                "loads are timing out while the message pipe displaced $displaced event(s) — " +
                    "consumers are slower than the relays, backpressure is the bottleneck"
            feedTimedOut.get() > 0L ->
                "loads are timing out without backpressure displacement — check relay reachability"
            worst > 8_000L ->
                "slow but completing (worst ${worst} ms) — likely relay latency, not CPU"
            else ->
                "healthy (worst ${worst} ms)"
        }
    }

    private fun formatAge(atMillis: Long): String {
        val ageSeconds = ((System.currentTimeMillis() - atMillis) / 1_000L).coerceAtLeast(0L)
        return if (ageSeconds < 60) "${ageSeconds}s ago" else "${ageSeconds / 60}m ago"
    }

    /** Test hook: wipe counters between tests. */
    internal fun reset() {
        launches.set(0)
        feedLoads.set(0)
        feedSucceeded.set(0)
        feedTimedOut.set(0)
        verifyChecks.set(0)
        verifySkipped.set(0)
        relayDropped = 0
        relayEvents = 0
        activeSubscriptions = 0
        connectedRelays = 0
        synchronized(lock) {
            feedSamples.clear()
            metadataSamples.clear()
        }
    }

    // Declared last on purpose: a constructor-time `init` block here would run
    // before the counters above are assigned (Kotlin initialises in declaration
    // order), which crashes on first construction.
    init {
        // Events are verified all over the app; publish the instance once so
        // Event.verify() can count without a Hilt dependency on a hot path.
        social.tbone.nostr.StartupMetricsProbe.metrics = this
        onProcessStart()
    }

    companion object {
        private const val MAX_SAMPLES = 40
        private const val MAX_REPORTED = 10
    }
}
