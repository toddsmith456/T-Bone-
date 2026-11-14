package social.tbone.ui.feed

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.db.EventRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayHintCache
import social.tbone.nostr.relay.RelayHintPolicy
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, debounced, 2-stage quote/repost resolver for Feed, Profile, Hashtag,
 * Thread, and Feed Viewer timelines.
 *
 * Key guarantees:
 *  1. **Zero premature "unavailable" poisoning:** backed by [QuoteLookupTracker],
 *     so resolving one quote from cache or a fast relay never marks other
 *     still-in-flight quotes unavailable.
 *  2. **Coalesced & chunked REQs:** incoming requests debounce for 150 ms and
 *     batch into ≤50-ID filters, eliminating subscription-flood `CLOSED` errors.
 *  3. **Metadata-only relays ignored for EOSE:** `purplepag.es` answering EOSE
 *     in 40 ms never ends a note lookup while general relays are still responding.
 *  4. **Stage-2 Read-Only Fallback (Option 2A):** for IDs still missing after
 *     personal relays finish, optionally checks sanitized embedded relay hints
 *     (`followRelayHintsForMissingNotes`) and/or built-in Fediverse bridge relays
 *     (`fediverseBridgeRelaysEnabled`) via short-lived scoped leases that are
 *     strictly Tor-safe, never persisted, and released immediately in `finally`.
 */
class QuoteLookupSession(
    private val scope: CoroutineScope,
    private val pool: RelayPool,
    private val extraPool: RelayPool? = null,
    private val eventRepository: EventRepository,
    private val appSettings: AppSettings,
    private val labelPrefix: String,
    private val onResolved: (Event) -> Unit = {},
) {
    private val tracker = QuoteLookupTracker()

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    private val _unresolvedQuoteIds = MutableStateFlow<Set<String>>(emptySet())
    val unresolvedQuoteIds: StateFlow<Set<String>> = _unresolvedQuoteIds.asStateFlow()

    private class ActiveSub(
        val subId: String,
        val ids: Set<String>,
        val expectedNoteRelays: Set<String>,
        val useExtraPool: Boolean,
        val answeredRelays: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val done: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private val activeSubs = ConcurrentHashMap<String, ActiveSub>()
    private val pendingLock = Any()
    private val pendingIds = LinkedHashSet<String>()
    private val pendingHints = HashMap<String, MutableList<String>>()
    private var debounceJob: Job? = null
    private val activeBatchJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private val leasedRelaysLock = Any()
    private val leasedRelayCounts = HashMap<String, Int>()
    private var collectorJob: Job? = null
    private var collectorReady: CompletableDeferred<Unit>? = null
    @Volatile private var sessionEpoch = 0L

    /** Extracts embedded reposts & enqueues missing quote/repost targets for [events]. */
    fun requestForEvents(events: List<Event>) {
        if (events.isEmpty()) return
        val hintsById = LinkedHashMap<String, MutableList<String>>()
        val toEnqueue = mutableListOf<String>()

        for (event in events) {
            val extracted = RelayHintPolicy.extractReferencedEventHints(event)
            if (extracted.isNotEmpty()) {
                RelayHintCache.putAll(extracted)
            }
            when (event.kind) {
                EventKind.REPOST, EventKind.GENERIC_REPOST -> {
                    val embedded = if (event.content.length > Tunables.MAX_REPOST_CONTENT_BYTES) {
                        null
                    } else {
                        runCatching { Event.fromJson(event.content) }.getOrNull()
                    }
                    if (embedded != null &&
                        !EventKind.isPrivateMessaging(embedded.kind) &&
                        embedded.verify()
                    ) {
                        deliverResolved(embedded, saveToDb = true, recurse = embedded.kind != EventKind.REPOST && embedded.kind != EventKind.GENERIC_REPOST)
                    } else {
                        extracted.forEach { (id, hints) ->
                            toEnqueue += id
                            if (hints.isNotEmpty()) {
                                hintsById.getOrPut(id) { mutableListOf() }.addAll(hints)
                            }
                        }
                    }
                }
                EventKind.TEXT_NOTE, EventKind.COMMENT, EventKind.POLL -> {
                    extracted.forEach { (id, hints) ->
                        toEnqueue += id
                        if (hints.isNotEmpty()) {
                            hintsById.getOrPut(id) { mutableListOf() }.addAll(hints)
                        }
                    }
                }
                else -> Unit
            }
        }

        if (toEnqueue.isNotEmpty()) {
            requestIds(toEnqueue, hintsById)
        }
    }

    /** Enqueues explicit event IDs (e.g. pinned profile notes) with optional raw relay hints. */
    fun requestIds(ids: Collection<String>, hintsById: Map<String, List<String>> = emptyMap()) {
        if (ids.isEmpty()) return
        if (hintsById.isNotEmpty()) RelayHintCache.putAll(hintsById)
        val newlyQueued = tracker.enqueue(ids)
        if (newlyQueued.isEmpty()) return

        synchronized(pendingLock) {
            pendingIds.addAll(newlyQueued)
            hintsById.forEach { (id, hints) ->
                if (hints.isNotEmpty()) {
                    pendingHints.getOrPut(id) { mutableListOf() }.addAll(hints)
                }
            }
            if (debounceJob?.isActive != true) {
                val epoch = sessionEpoch
                debounceJob = scope.launch(Dispatchers.Default) {
                    delay(DEBOUNCE_MS)
                    if (epoch != sessionEpoch) return@launch
                    flushPending(epoch)
                }
            }
        }
    }

    /** Clears pending lookups, active subscriptions, and unresolved state (e.g. on refresh/switch). */
    fun reset(clearResolved: Boolean = false) {
        sessionEpoch++
        synchronized(pendingLock) {
            debounceJob?.cancel()
            debounceJob = null
            pendingIds.clear()
            pendingHints.clear()
        }
        activeBatchJobs.toList().forEach { it.cancel() }
        activeBatchJobs.clear()
        activeSubs.keys.toList().forEach { subId ->
            activeSubs.remove(subId)?.let { sub ->
                pool.unsubscribe(subId)
                if (sub.useExtraPool) extraPool?.unsubscribe(subId)
                sub.done.complete(Unit)
            }
        }
        releaseAllLeasedRelays()
        tracker.reset(clearResolved = clearResolved)
        if (clearResolved) _quotedEvents.value = emptyMap()
        _unresolvedQuoteIds.value = emptySet()
    }

    /** Tears down collector, subscriptions, and temporary relay leases (call from `onCleared`). */
    fun close() {
        reset(clearResolved = false)
        collectorJob?.cancel()
        collectorJob = null
        collectorReady = null
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private suspend fun ensureCollectorStarted() {
        val ready = synchronized(this) {
            val existingJob = collectorJob
            val existingReady = collectorReady
            if (existingJob?.isActive == true && existingReady != null) {
                existingReady
            } else {
                val personalReady = CompletableDeferred<Unit>()
                val extraReady = CompletableDeferred<Unit>()
                val combinedReady = CompletableDeferred<Unit>()
                collectorReady = combinedReady
                val flow = if (extraPool != null) {
                    merge(
                        pool.messages.onSubscription { personalReady.complete(Unit) },
                        extraPool.messages.onSubscription { extraReady.complete(Unit) },
                    )
                } else {
                    extraReady.complete(Unit)
                    pool.messages.onSubscription { personalReady.complete(Unit) }
                }
                collectorJob = scope.launch(Dispatchers.Default) {
                    launch {
                        personalReady.await()
                        extraReady.await()
                        combinedReady.complete(Unit)
                    }
                    flow.collect { pm ->
                        runCatching { handlePoolMessage(pm) }
                            .onFailure { t ->
                                if (t is CancellationException) throw t
                                Timber.w(t, "QuoteLookupSession message handling failed")
                            }
                    }
                }
                combinedReady
            }
        }
        ready.await()
    }

    private fun handlePoolMessage(pm: PoolMessage) {
        when (val msg = pm.message) {
            is RelayMessage.EventMessage -> {
                val sub = activeSubs[msg.subscriptionId] ?: return
                val event = msg.event
                if (EventKind.isPrivateMessaging(event.kind)) return
                if (!event.verify()) return
                deliverResolved(
                    event = event,
                    saveToDb = true,
                    recurse = event.kind != EventKind.REPOST && event.kind != EventKind.GENERIC_REPOST,
                )
                // If every target ID for this subscription has arrived, finish early!
                if (sub.ids.all { tracker.isResolved(it) }) {
                    sub.done.complete(Unit)
                }
            }
            is RelayMessage.EndOfStoredEvents -> {
                val sub = activeSubs[msg.subscriptionId] ?: return
                val normalized = RelayPool.normalize(pm.relayUrl)
                // Metadata-only relays (e.g. purplepag.es) never store notes; ignore their instant EOSE.
                if (RelayHintPolicy.isMetadataOnlyRelay(normalized)) return
                sub.answeredRelays.add(normalized)
                if (sub.expectedNoteRelays.isNotEmpty() &&
                    sub.answeredRelays.containsAll(sub.expectedNoteRelays)
                ) {
                    sub.done.complete(Unit)
                }
            }
            is RelayMessage.Closed -> {
                val sub = activeSubs[msg.subscriptionId] ?: return
                val normalized = RelayPool.normalize(pm.relayUrl)
                if (RelayHintPolicy.isMetadataOnlyRelay(normalized)) return
                sub.answeredRelays.add(normalized)
                if (sub.expectedNoteRelays.isNotEmpty() &&
                    sub.answeredRelays.containsAll(sub.expectedNoteRelays)
                ) {
                    sub.done.complete(Unit)
                }
            }
            else -> Unit
        }
    }

    private fun deliverResolved(event: Event, saveToDb: Boolean, recurse: Boolean) {
        _quotedEvents.update { it + (event.id to event) }
        _unresolvedQuoteIds.value = tracker.markResolved(listOf(event.id))
        if (saveToDb) {
            scope.launch(Dispatchers.IO) {
                runCatching { eventRepository.save(event, "") }
                    .onFailure { Timber.w(it, "Failed saving resolved quoted event ${event.id.take(8)}") }
            }
        }
        runCatching { onResolved(event) }
            .onFailure { Timber.w(it, "QuoteLookupSession onResolved callback failed") }
        if (recurse) {
            requestForEvents(listOf(event))
        }
    }

    private fun flushPending(epoch: Long) {
        val batchIds: List<String>
        val batchHints: Map<String, List<String>>
        synchronized(pendingLock) {
            if (pendingIds.isEmpty()) return
            batchIds = pendingIds.toList()
            pendingIds.clear()
            batchHints = batchIds.associateWith { id ->
                pendingHints.remove(id)?.distinct().orEmpty()
            }
        }

        val job = scope.launch(Dispatchers.Default) {
            try {
                resolveBatch(epoch, batchIds, batchHints)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.w(t, "QuoteLookupSession batch failed")
                if (epoch == sessionEpoch) {
                    _unresolvedQuoteIds.value = tracker.finishQueued(batchIds, markTerminal = false)
                }
            }
        }
        activeBatchJobs.add(job)
        job.invokeOnCompletion { activeBatchJobs.remove(job) }
    }

    private suspend fun resolveBatch(
        epoch: Long,
        batchIds: List<String>,
        batchHints: Map<String, List<String>>,
    ) {
        if (epoch != sessionEpoch) return

        // Step 0: Local Room cache (chunked inside EventRepository).
        // Resolving cached items updates ONLY those IDs — never poisons `stillMissing`!
        val cached = runCatching { eventRepository.getByIds(batchIds) }
            .getOrDefault(emptyList())
            .filterNot { EventKind.isPrivateMessaging(it.kind) }
            .associateBy { it.id }

        if (epoch != sessionEpoch) return

        if (cached.isNotEmpty()) {
            _quotedEvents.update { it + cached }
            _unresolvedQuoteIds.value = tracker.markResolved(cached.keys)
            cached.values.forEach { ev ->
                runCatching { onResolved(ev) }
                if (ev.kind != EventKind.REPOST && ev.kind != EventKind.GENERIC_REPOST) {
                    requestForEvents(listOf(ev))
                }
            }
        }

        val stillMissing = batchIds.filter { !tracker.isResolved(it) }
        if (stillMissing.isEmpty()) return

        ensureCollectorStarted()
        if (epoch != sessionEpoch) return

        // If relays are still warming up (cold launch / background resume / Tor circuit),
        // wait briefly for at least one note-capable relay to reach CONNECTED.
        if (connectedNoteRelays(pool).isEmpty() && connectedNoteRelays(extraPool).isEmpty()) {
            withTimeoutOrNull(CONNECT_WAIT_MS) {
                pool.relayStatuses.first { statuses ->
                    statuses.any { (url, st) ->
                        st == RelayStatus.CONNECTED && RelayHintPolicy.isNoteCapableRelay(url)
                    }
                }
            }
        }
        if (epoch != sessionEpoch) return

        // Process in safe ≤50-ID chunks so relays never reject oversized filters.
        stillMissing.chunked(MAX_FILTER_BATCH).forEach { chunk ->
            if (epoch != sessionEpoch) return
            resolveChunk(epoch, chunk, batchHints)
        }
    }

    private suspend fun resolveChunk(
        epoch: Long,
        chunk: List<String>,
        batchHints: Map<String, List<String>>,
    ) {
        val pendingChunk = chunk.filter { !tracker.isResolved(it) }
        if (pendingChunk.isEmpty()) return

        val stage1RelaysPersonal = connectedNoteRelays(pool)
        val stage1RelaysExtra = connectedNoteRelays(extraPool)
        val stage1Expected = stage1RelaysPersonal + stage1RelaysExtra
        val useExtra = extraPool != null && stage1RelaysExtra.isNotEmpty()

        val stage1SubId = "$labelPrefix-q1-${UUID.randomUUID().toString().take(8)}"
        val stage1Sub = ActiveSub(
            subId = stage1SubId,
            ids = pendingChunk.toSet(),
            expectedNoteRelays = stage1Expected,
            useExtraPool = useExtra,
        )
        tracker.startSubscription(stage1SubId, pendingChunk)
        activeSubs[stage1SubId] = stage1Sub

        val queriedInStage1 = stage1Expected.map { RelayPool.normalize(it).lowercase() }.toMutableSet()

        try {
            val filters = listOf(Filter(ids = pendingChunk))
            // Query already-connected pools first (never opens new connections in Stage 1).
            pool.subscribe(filters, id = stage1SubId, label = "$labelPrefix-quote")
            if (useExtra) {
                extraPool?.subscribe(filters, id = stage1SubId, label = "$labelPrefix-quote")
            }

            val fallbackPreview = computeFallbackRelays(pendingChunk, batchHints, queriedInStage1)
            val stage1Timeout = when {
                stage1Expected.isEmpty() && fallbackPreview.isNotEmpty() -> EMPTY_STAGE1_WAIT_MS
                fallbackPreview.isNotEmpty() -> STAGE1_TIMEOUT_WITH_FALLBACK_MS
                else -> STAGE1_TIMEOUT_ONLY_MS
            }
            withTimeoutOrNull(stage1Timeout) { stage1Sub.done.await() }
        } finally {
            activeSubs.remove(stage1SubId)
            pool.unsubscribe(stage1SubId)
            if (useExtra) extraPool?.unsubscribe(stage1SubId)
        }

        if (epoch != sessionEpoch) return

        val missingAfterStage1 = pendingChunk.filter { !tracker.isResolved(it) }
        if (missingAfterStage1.isEmpty()) {
            _unresolvedQuoteIds.value = tracker.finishSubscription(stage1SubId)
            return
        }

        // Also count any relay that actually answered in Stage 1 so Stage 2 never re-queries it.
        stage1Sub.answeredRelays.forEach { queriedInStage1 += RelayPool.normalize(it).lowercase() }

        // Stage 2 (Option 2A): strictly read-only, Tor-safe, SSRF-filtered fallback
        // using embedded relay hints and/or built-in Fediverse bridge relays if enabled.
        val fallbackRelays = computeFallbackRelays(missingAfterStage1, batchHints, queriedInStage1)
        if (fallbackRelays.isEmpty()) {
            _unresolvedQuoteIds.value = tracker.finishSubscription(stage1SubId)
            return
        }

        // Keep these IDs in "loading…" state while Stage 2 runs!
        _unresolvedQuoteIds.value = tracker.finishSubscription(
            subId = stage1SubId,
            keepPending = missingAfterStage1.toSet(),
        )

        val stage2SubId = "$labelPrefix-q2-${UUID.randomUUID().toString().take(8)}"
        val stage2Sub = ActiveSub(
            subId = stage2SubId,
            ids = missingAfterStage1.toSet(),
            expectedNoteRelays = fallbackRelays,
            useExtraPool = false,
        )
        tracker.startSubscription(stage2SubId, missingAfterStage1)
        activeSubs[stage2SubId] = stage2Sub

        val acquired = acquireFallbackRelays(fallbackRelays)
        try {
            val filters = listOf(Filter(ids = missingAfterStage1))
            // Scoped strictly to fallback relays — zero extra traffic on personal relays.
            pool.subscribeTo(
                filters = filters,
                id = stage2SubId,
                label = "$labelPrefix-quote-fb",
                relays = fallbackRelays,
            )
            val stage2Timeout = if (appSettings.torEnabled.value) STAGE2_TIMEOUT_TOR_MS else STAGE2_TIMEOUT_MS
            withTimeoutOrNull(stage2Timeout) { stage2Sub.done.await() }
        } finally {
            activeSubs.remove(stage2SubId)
            pool.unsubscribe(stage2SubId)
            releaseFallbackRelays(acquired)
            if (epoch == sessionEpoch) {
                _unresolvedQuoteIds.value = tracker.finishSubscription(stage2SubId)
            }
        }
    }

    private fun computeFallbackRelays(
        missingIds: List<String>,
        batchHints: Map<String, List<String>>,
        alreadyQueriedLower: Set<String>,
    ): Set<String> {
        val torEnabled = appSettings.torEnabled.value
        val allowHints = appSettings.followRelayHintsForMissingNotes.value
        val allowBridges = appSettings.fediverseBridgeRelaysEnabled.value
        if (!allowHints && !allowBridges) return emptySet()

        val candidates = LinkedHashSet<String>()
        if (allowHints) {
            for (id in missingIds) {
                val rawHints = (batchHints[id].orEmpty() + RelayHintCache.get(id)).distinct()
                for (raw in rawHints) {
                    val clean = RelayHintPolicy.sanitizeHintUrl(raw, torEnabled) ?: continue
                    if (clean.lowercase() !in alreadyQueriedLower) {
                        candidates += clean
                        if (candidates.size >= MAX_FALLBACK_RELAYS) break
                    }
                }
                if (candidates.size >= MAX_FALLBACK_RELAYS) break
            }
        }
        if (allowBridges) {
            for (bridge in Tunables.FEDIVERSE_BRIDGE_RELAYS) {
                val clean = RelayHintPolicy.sanitizeHintUrl(bridge, torEnabled) ?: continue
                if (clean.lowercase() !in alreadyQueriedLower) {
                    candidates += clean
                    if (candidates.size >= MAX_FALLBACK_RELAYS) break
                }
            }
        }
        return candidates
    }

    private fun acquireFallbackRelays(relays: Set<String>): List<String> {
        val acquired = mutableListOf<String>()
        synchronized(leasedRelaysLock) {
            relays.forEach { url ->
                val normalized = RelayPool.normalize(url)
                leasedRelayCounts[normalized] = (leasedRelayCounts[normalized] ?: 0) + 1
                pool.acquireRelay(normalized)
                acquired += normalized
            }
        }
        return acquired
    }

    private fun releaseFallbackRelays(relays: List<String>) {
        synchronized(leasedRelaysLock) {
            relays.forEach { normalized ->
                val count = leasedRelayCounts[normalized] ?: return@forEach
                if (count <= 1) leasedRelayCounts.remove(normalized)
                else leasedRelayCounts[normalized] = count - 1
                pool.releaseRelay(normalized)
            }
        }
    }

    private fun releaseAllLeasedRelays() {
        val snapshot: List<Pair<String, Int>>
        synchronized(leasedRelaysLock) {
            snapshot = leasedRelayCounts.entries.map { it.key to it.value }
            leasedRelayCounts.clear()
        }
        snapshot.forEach { (url, count) ->
            repeat(count) { pool.releaseRelay(url) }
        }
    }

    private fun connectedNoteRelays(targetPool: RelayPool?): Set<String> {
        if (targetPool == null) return emptySet()
        return targetPool.relayStatuses.value.entries
            .asSequence()
            .filter { (url, status) ->
                status == RelayStatus.CONNECTED && RelayHintPolicy.isNoteCapableRelay(url)
            }
            .map { RelayPool.normalize(it.key) }
            .toSet()
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
        private const val MAX_FILTER_BATCH = 50
        private const val MAX_FALLBACK_RELAYS = 6
        private const val CONNECT_WAIT_MS = 2_500L
        private const val EMPTY_STAGE1_WAIT_MS = 1_500L
        private const val STAGE1_TIMEOUT_WITH_FALLBACK_MS = 5_500L
        private const val STAGE1_TIMEOUT_ONLY_MS = 10_000L
        private const val STAGE2_TIMEOUT_MS = 7_500L
        private const val STAGE2_TIMEOUT_TOR_MS = 12_000L
    }
}
