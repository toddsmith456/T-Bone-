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
    private val retryOnConnectIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
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

        enqueuePending(newlyQueued, hintsById, immediate = false)
    }

    /**
     * Retries a single referenced note that previously ended as unavailable.
     * Clears its terminal state immediately (so the card switches back to
     * "loading…") and launches a fresh lookup using all cached relay hints and
     * enabled fallback relays.
     */
    fun retryId(id: String) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        retryOnConnectIds.remove(clean)
        val (queued, updatedUnresolved) = tracker.retry(clean)
        _unresolvedQuoteIds.value = updatedUnresolved
        if (!queued) return
        val cachedHints = RelayHintCache.get(clean)
        val hintsMap = if (cachedHints.isNotEmpty()) mapOf(clean to cachedHints) else emptyMap()
        enqueuePending(listOf(clean), hintsMap, immediate = true)
    }

    private fun enqueuePending(
        ids: List<String>,
        hintsById: Map<String, List<String>>,
        immediate: Boolean,
    ) {
        if (immediate) {
            val epoch = sessionEpoch
            val batchHints = ids.associateWith { id ->
                hintsById[id]?.distinct().orEmpty()
            }
            launchBatch(epoch, ids, batchHints)
            return
        }
        synchronized(pendingLock) {
            pendingIds.addAll(ids)
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
        retryOnConnectIds.clear()
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
                    // Auto-retry any quotes that previously got zero relay answers
                    // (offline / cold-start / Tor bootstrapping) once a note-capable relay connects.
                    launch {
                        val statusFlow = if (extraPool != null) {
                            merge(pool.relayStatuses, extraPool.relayStatuses)
                        } else {
                            pool.relayStatuses
                        }
                        statusFlow.collect { statuses ->
                            if (retryOnConnectIds.isEmpty()) return@collect
                            val hasConnectedNoteRelay = statuses.any { (url, st) ->
                                st == RelayStatus.CONNECTED && RelayHintPolicy.isNoteCapableRelay(url)
                            }
                            if (hasConnectedNoteRelay) {
                                val toRetry = retryOnConnectIds.toList()
                                retryOnConnectIds.removeAll(toRetry.toSet())
                                if (toRetry.isNotEmpty()) {
                                    requestIds(toRetry)
                                }
                            }
                        }
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
        retryOnConnectIds.remove(event.id)
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
        launchBatch(epoch, batchIds, batchHints)
    }

    private fun launchBatch(
        epoch: Long,
        batchIds: List<String>,
        batchHints: Map<String, List<String>>,
    ) {
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
            retryOnConnectIds.removeAll(cached.keys)
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

        // Wait for AppSettings to finish loading before issuing network lookups so
        // cold-start requests never race ahead of NetworkModule's Tor-guard transition
        // and initial setPersonalRelays().
        if (!appSettings.initialized.value) {
            withTimeoutOrNull(SETTINGS_INIT_WAIT_MS) {
                appSettings.initialized.first { it }
            }
        }
        if (epoch != sessionEpoch) return

        // If relays are still warming up (cold launch / background resume / Tor circuit),
        // wait briefly for at least one note-capable relay to reach CONNECTED.
        if (connectedNoteRelays(pool).isEmpty() && connectedNoteRelays(extraPool).isEmpty()) {
            withTimeoutOrNull(CONNECT_WAIT_MS) {
                val statusFlow = if (extraPool != null) {
                    merge(pool.relayStatuses, extraPool.relayStatuses)
                } else {
                    pool.relayStatuses
                }
                statusFlow.first { statuses ->
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

        // Include BOTH CONNECTED and CONNECTING note-capable relays in Stage-1
        // expectations so a single fast relay's empty EOSE cannot prematurely close
        // Stage 1 while the user's other personal relays are still handshaking.
        val stage1RelaysPersonal = activeNoteRelays(pool)
        val stage1RelaysExtra = activeNoteRelays(extraPool)
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

        val hasAnyFallbackEnabled = appSettings.followRelayHintsForMissingNotes.value ||
            appSettings.fediverseBridgeRelaysEnabled.value

        try {
            val filters = listOf(Filter(ids = pendingChunk))
            // Query personal / active pools first (never opens new connections in Stage 1).
            pool.subscribe(filters, id = stage1SubId, label = "$labelPrefix-quote")
            if (useExtra) {
                extraPool?.subscribe(filters, id = stage1SubId, label = "$labelPrefix-quote")
            }

            val stage1Timeout = when {
                stage1Expected.isEmpty() && hasAnyFallbackEnabled -> EMPTY_STAGE1_WITH_FALLBACK_WAIT_MS
                stage1Expected.isEmpty() -> EMPTY_STAGE1_WAIT_MS
                hasAnyFallbackEnabled -> STAGE1_TIMEOUT_WITH_FALLBACK_MS
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

        // Only treat relays that ACTUALLY answered EOSE/CLOSED in Stage 1 as already
        // checked — never skip a hint/bridge relay that was disconnected or timed out.
        val answeredInStage1 = stage1Sub.answeredRelays
            .map { RelayPool.normalize(it) }
            .toSet()

        // Stage 2 (Option 2A): strictly read-only, Tor-safe, SSRF-filtered fallback
        // planned into bounded waves so multi-note batches never starve hints or bridges.
        val fallbackWaves = computeFallbackWaves(missingAfterStage1, batchHints, answeredInStage1)
        if (fallbackWaves.isEmpty()) {
            val hadAnyAnswer = answeredInStage1.isNotEmpty()
            _unresolvedQuoteIds.value = tracker.finishSubscription(
                subId = stage1SubId,
                markTerminal = hadAnyAnswer,
            )
            if (!hadAnyAnswer) {
                retryOnConnectIds.addAll(missingAfterStage1)
            }
            return
        }

        // Keep missing IDs in "loading…" state across Stage-2 waves.
        _unresolvedQuoteIds.value = tracker.finishSubscription(
            subId = stage1SubId,
            keepPending = missingAfterStage1.toSet(),
        )

        var totalAnswered = answeredInStage1.size
        val stage2Timeout = if (appSettings.torEnabled.value) STAGE2_TIMEOUT_TOR_MS else STAGE2_TIMEOUT_MS

        for ((waveIndex, waveRelays) in fallbackWaves.withIndex()) {
            if (epoch != sessionEpoch) return
            val waveMissing = missingAfterStage1.filter { !tracker.isResolved(it) }
            if (waveMissing.isEmpty()) break

            val waveSubId = "$labelPrefix-q2-${UUID.randomUUID().toString().take(8)}"
            val waveSub = ActiveSub(
                subId = waveSubId,
                ids = waveMissing.toSet(),
                expectedNoteRelays = waveRelays,
                useExtraPool = false,
            )
            tracker.startSubscription(waveSubId, waveMissing)
            activeSubs[waveSubId] = waveSub

            var acquired = emptyList<String>()
            try {
                val filters = listOf(Filter(ids = waveMissing))
                // Subscribe BEFORE acquiring relays so a fast WebSocket handshake can
                // never emit Connected before activeSubscriptions holds waveSubId.
                pool.subscribeTo(
                    filters = filters,
                    id = waveSubId,
                    label = "$labelPrefix-quote-fb",
                    relays = waveRelays,
                )
                acquired = acquireFallbackRelays(waveRelays)
                withTimeoutOrNull(stage2Timeout) { waveSub.done.await() }
            } finally {
                activeSubs.remove(waveSubId)
                pool.unsubscribe(waveSubId)
                if (acquired.isNotEmpty()) releaseFallbackRelays(acquired)
            }

            if (epoch != sessionEpoch) return
            totalAnswered += waveSub.answeredRelays.size

            val stillMissingAfterWave = waveMissing.filter { !tracker.isResolved(it) }
            val hasMoreWaves = waveIndex < fallbackWaves.lastIndex && stillMissingAfterWave.isNotEmpty()
            if (hasMoreWaves) {
                _unresolvedQuoteIds.value = tracker.finishSubscription(
                    subId = waveSubId,
                    keepPending = stillMissingAfterWave.toSet(),
                )
            } else {
                val hadAnyAnswer = totalAnswered > 0
                _unresolvedQuoteIds.value = tracker.finishSubscription(
                    subId = waveSubId,
                    markTerminal = hadAnyAnswer,
                )
                if (!hadAnyAnswer && stillMissingAfterWave.isNotEmpty()) {
                    retryOnConnectIds.addAll(stillMissingAfterWave)
                }
            }
        }
    }

    private fun computeFallbackWaves(
        missingIds: List<String>,
        batchHints: Map<String, List<String>>,
        answeredInStage1: Set<String>,
    ): List<Set<String>> {
        val mergedHints = missingIds.associateWith { id ->
            (batchHints[id].orEmpty() + RelayHintCache.get(id)).distinct()
        }
        return RelayHintPolicy.planFallbackWaves(
            missingIds = missingIds,
            hintsById = mergedHints,
            answeredStage1Relays = answeredInStage1,
            allowHints = appSettings.followRelayHintsForMissingNotes.value,
            allowBridges = appSettings.fediverseBridgeRelaysEnabled.value,
            torEnabled = appSettings.torEnabled.value,
            maxPerWave = MAX_FALLBACK_RELAYS_PER_WAVE,
            maxHintWaves = MAX_HINT_WAVES,
        )
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

    private fun activeNoteRelays(targetPool: RelayPool?): Set<String> {
        if (targetPool == null) return emptySet()
        return targetPool.relayStatuses.value.entries
            .asSequence()
            .filter { (url, status) ->
                (status == RelayStatus.CONNECTED || status == RelayStatus.CONNECTING) &&
                    RelayHintPolicy.isNoteCapableRelay(url)
            }
            .map { RelayPool.normalize(it.key) }
            .toSet()
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
        private const val MAX_FILTER_BATCH = 50
        private const val MAX_FALLBACK_RELAYS_PER_WAVE = RelayHintPolicy.DEFAULT_MAX_FALLBACK_PER_WAVE
        private const val MAX_HINT_WAVES = RelayHintPolicy.DEFAULT_MAX_HINT_WAVES
        private const val SETTINGS_INIT_WAIT_MS = 2_000L
        private const val CONNECT_WAIT_MS = 3_000L
        private const val EMPTY_STAGE1_WITH_FALLBACK_WAIT_MS = 800L
        private const val EMPTY_STAGE1_WAIT_MS = 1_500L
        private const val STAGE1_TIMEOUT_WITH_FALLBACK_MS = 5_500L
        private const val STAGE1_TIMEOUT_ONLY_MS = 10_000L
        private const val STAGE2_TIMEOUT_MS = 7_500L
        private const val STAGE2_TIMEOUT_TOR_MS = 12_000L
    }
}
