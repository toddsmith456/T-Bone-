package social.tbone.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.Tunables
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
import social.tbone.settings.ContentFilter
import social.tbone.reactions.ReactionsRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayHintCache
import social.tbone.nostr.relay.RelayHintPolicy
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayStatus
import social.tbone.ui.feed.QuoteLookupSession
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.replyEventId
import social.tbone.nostr.rootEventId
import social.tbone.nostr.threadRootEventId
import social.tbone.profile.ProfileRepository
import social.tbone.reactions.PollsRepository
import social.tbone.reactions.RepliesRepository
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class ThreadUiState(
    /** Topmost ancestor we could resolve; equals [focused] for a top-level note. */
    val root: Event? = null,
    /** The note that was tapped. */
    val focused: Event? = null,
    val focusedEventId: String = "",
    /** Flattened render list (root → ancestors → focused → mini threads). */
    val items: List<ThreadItem> = emptyList(),
    val isLoading: Boolean = true,
    /** The tapped note could not be found on any relay. */
    val notFound: Boolean = false,
    /** Ancestors above the focused note are still being fetched. */
    val loadingContext: Boolean = false,
    /** The focused note is currently hidden by the viewer's content filters. */
    val contentFiltered: Boolean = false,
) {
    val replyCount: Int get() = items.count { it is ThreadItem.Note && it.depth > 0 }
}

/**
 * Thread screen state.
 *
 * Loading is deliberately bounded at every step (see [EOSE_TIMEOUT_MS]): every
 * relay wait has a timeout, so a silent or slow relay can no longer leave the
 * screen spinning with a parent note that never appears.
 *
 * The whole ancestor chain between the root and the tapped note is resolved and
 * rendered — the old build fetched only the root and the direct parent and
 * showed a "replies in between" placeholder for everything between them.
 */
@HiltViewModel
class ThreadViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val reactionsRepository: ReactionsRepository,
    private val repliesRepository: RepliesRepository,
    private val appSettings: social.tbone.settings.AppSettings,
    private val muteRepository: social.tbone.lists.MuteListRepository,
    private val pollsRepository: PollsRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val eventId: String = checkNotNull(savedStateHandle["eventId"])

    private val _uiState = MutableStateFlow(ThreadUiState(focusedEventId = eventId))
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val metadataLeases = MetadataLeases()

    private val quoteLookup = QuoteLookupSession(
        scope = viewModelScope,
        pool = pool,
        eventRepository = eventRepository,
        appSettings = appSettings,
        labelPrefix = "thread",
        onResolved = { event -> fetchMetadataForAuthors(listOf(event.pubkey)) },
    )

    val quotedEvents: StateFlow<Map<String, Event>> = quoteLookup.quotedEvents

    /**
     * Referenced notes whose lookup genuinely finished without returning an
     * event, so cards show a terminal "not available" row.
     */
    val unresolvedQuoteIds: StateFlow<Set<String>> = quoteLookup.unresolvedQuoteIds

    val reactions: StateFlow<Map<String, Set<String>>> = reactionsRepository.reactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val replies: StateFlow<Map<String, Int>> = repliesRepository.replies
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val repliedByMe: StateFlow<Set<String>> = repliesRepository.repliedByMe
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val pollVoteCounts: StateFlow<Map<String, Map<String, Int>>> = pollsRepository.counts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pollTotalVotes: StateFlow<Map<String, Int>> = pollsRepository.totalVotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pollMyVotes: StateFlow<Map<String, List<String>>> = pollsRepository.myVotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val pollVoteVersion: StateFlow<Int> = pollsRepository.voteVersion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var collectJob: Job? = null

    /** Every event known to be part of this thread, keyed by id. */
    private val threadEvents = ConcurrentHashMap<String, Event>()

    private var rootId: String? = null

    /** Subscriptions opened by [fetchById]; their events bypass the thread filter. */
    private val fetchSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val fetchedById = ConcurrentHashMap<String, Event>()

    /** Hint / fallback relays connected for this screen; released in onCleared. */
    private val acquiredRelays: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Notes whose own replies have been requested (nested reply discovery). */
    private val repliesRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingReplyLookups: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var replyLookupJob: Job? = null


    /**
     * EOSE bookkeeping. Relays answer a REQ at different speeds, and the old
     * build treated the *first* EndOfStoredEvents as "everything has arrived",
     * so a slow relay's copy of the parent note was never seen. Here we count
     * EOSE per relay and wait for all connected ones — with a hard timeout so a
     * silent relay can never hang the screen.
     */
    private val eoseRelays = ConcurrentHashMap<String, MutableSet<String>>()
    private val eoseSignals = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Followed accounts, for the mute-inheritance exemption.
     *
     * MUST be declared before [init]: the init block starts a collector that can
     * call [rebuild] before construction finishes (effectiveMuted is an eagerly
     * started StateFlow), and rebuild() reads this. Declared after init the field
     * is still null at that point and reading it threw a NullPointerException —
     * the crash when opening a reply from the notifications tab.
     */
    private val followedPubkeys: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        accountRepository.activeAccount
            .map { it?.follows?.toSet().orEmpty() }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Eagerly,
                emptySet(),
            )

    init {
        // Two coroutines on purpose: the message collector must be running while
        // load() is waiting for relays, otherwise the EndOfStoredEvents that
        // releases each wait could only arrive after loading had finished.
        collectJob = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            pool.messages.collect { handlePoolMessage(it) }
        }
        viewModelScope.launch(Dispatchers.Default) { load() }
        viewModelScope.launch {
            combine(
                muteRepository.effectiveMuted,
                appSettings.nsfwMode,
                appSettings.hideWords,
            ) { _, _, _ -> Unit }.collect { rebuild() }
        }
    }

    fun boost(event: Event) {
        viewModelScope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.REPOST,
                content = Json.encodeToString(Event.serializer(), event),
                tags = listOf(
                    buildJsonArray { add("e"); add(event.id); add(""); add("mention") },
                    buildJsonArray { add("p"); add(event.pubkey) },
                ),
            )
            signer.signEvent(unsigned)
                .onSuccess { pool.publish(it) }
                .onFailure { e -> Timber.w(e, "Boost failed") }
        }
    }

    fun react(event: Event) = reactionsRepository.react(event)

    fun voteOnPoll(poll: Event, optionIds: List<String>) =
        pollsRepository.vote(poll, optionIds)

    // ── Loading ───────────────────────────────────────────────────────────────

    private suspend fun load() {
        var focused = eventRepository.getById(eventId) ?: fetchById(eventId)
        if (focused == null) {
            Timber.w("thread: focused note $eventId not found on any relay")
            _uiState.update { it.copy(isLoading = false, notFound = true) }
            return
        }

        // A repost (kind 6 / 16) is only a wrapper: the thread that matters is
        // the one of the note that was reposted. Without this a reposted reply
        // opened as its wrapper had no parent tags at all, so nothing upstream
        // ever loaded.
        unwrapRepost(focused)?.let { focused = it }
        val target = focused!!

        threadEvents[target.id] = target
        _uiState.update {
            it.copy(
                focused = target,
                focusedEventId = target.id,
                isLoading = false,
                loadingContext = true,
            )
        }
        rebuild()

        // Start listening for replies to the focused note immediately so they
        // stream in while the ancestors are still being fetched.
        val declaredRoot = target.parsedTags.threadRootEventId?.takeIf { it != target.id }
        rootId = declaredRoot ?: target.id
        startThreadSubscriptions(declaredRoot ?: target.id, listOf(target.id))

        // Walk all the way up so every note between the root and this one is
        // actually fetched (this is the old "replies in between" gap).
        val ancestors = resolveAncestors(target)
        ancestors.forEach { threadEvents[it.id] = it }
        val root = ancestors.firstOrNull() ?: target
        // If the root itself could not be fetched, still anchor the thread to
        // the declared root id so sibling replies are requested and grouped.
        rootId = if (ancestors.isEmpty() && declaredRoot != null) declaredRoot else root.id
        _uiState.update { it.copy(root = root, loadingContext = false) }

        val chain = (ancestors.map { it.id } + target.id).distinct()
        startThreadSubscriptions(rootId ?: root.id, chain)
        rebuild()

        fetchProfiles(threadEvents.values.toList())
        fetchQuotesForEvents(threadEvents.values.toList())
        reactionsRepository.subscribeTo(threadEvents.keys.toList())
        repliesRepository.subscribeTo(threadEvents.keys.toList())
        fetchPollsFor(threadEvents.values.toList())
    }

    /**
     * Returns the reposted note when [event] is a kind-6 / kind-16 repost
     * (embedded JSON first, then the `e` tag fetched from relays).
     */
    private suspend fun unwrapRepost(event: Event): Event? {
        if (event.kind != EventKind.REPOST && event.kind != EventKind.GENERIC_REPOST) return null
        val embedded = if (event.content.isBlank() || event.content.length > MAX_REPOST_CONTENT_BYTES) {
            null
        } else {
            runCatching { Event.fromJson(event.content) }.getOrNull()?.takeIf { it.verify() }
        }
        if (embedded != null) {
            viewModelScope.launch { eventRepository.save(embedded, "") }
            return embedded
        }
        val tag = event.parsedTags.firstOrNull { it.name == "e" } ?: return null
        val id = tag.value() ?: return null
        val hint = tag.value(2)?.trim()
            ?.takeIf { it.startsWith("wss://") || it.startsWith("ws://") }
        return eventRepository.getById(id) ?: fetchById(id, listOfNotNull(hint))
    }

    /**
     * Walks `replyEventId` upwards from [focused] until the chain ends,
     * fetching missing ancestors from the relays (cache first, then every
     * connected relay, then the relay hints in the tags plus fallback relays).
     * When a direct parent genuinely cannot be found, the declared thread root
     * is tried so the top of the conversation still shows. Returns root-first.
     */
    private suspend fun resolveAncestors(focused: Event): List<Event> {
        val chain = mutableListOf<Event>()
        val seen = mutableSetOf(focused.id)
        var current: Event = focused
        var budget = MAX_ANCESTOR_HOPS

        while (budget-- > 0) {
            val parentId = current.parsedTags.replyEventId
                ?: current.parsedTags.threadRootEventId?.takeIf { it != current.id }
                ?: break
            if (!seen.add(parentId)) break // cycle guard

            var parent = eventRepository.getById(parentId)
                ?: fetchById(parentId, relayHintsFor(current, parentId))
            if (parent == null) {
                // Parent missing everywhere — jump to the thread root instead
                // of giving up on everything above.
                val rootRef = current.parsedTags.threadRootEventId
                    ?.takeIf { it != parentId && it !in seen }
                if (rootRef != null) {
                    seen += rootRef
                    parent = eventRepository.getById(rootRef)
                        ?: fetchById(rootRef, relayHintsFor(current, rootRef))
                }
            }
            if (parent == null) break
            chain += parent
            threadEvents[parent.id] = parent
            // Show each ancestor as soon as it arrives.
            rebuild()
            current = parent
        }

        return chain.reversed() // root-first
    }

    /** Relay hints carried by [event]'s e/E/q tags for [targetId]. */
    private fun relayHintsFor(event: Event, targetId: String): List<String> =
        event.parsedTags
            .filter { (it.name == "e" || it.name == "E" || it.name == "q") && it.value() == targetId }
            .mapNotNull { it.value(2)?.trim()?.takeIf { url -> url.startsWith("wss://") || url.startsWith("ws://") } }
            .distinct()

    /**
     * Fetches one event by id. Returns as soon as any relay delivers it; gives
     * up after every relay answered EOSE (bounded by [EOSE_TIMEOUT_MS]). When
     * the connected relays don't have it, retries once on the [hints] plus the
     * fallback relays, which are connected temporarily for this screen.
     *
     * The previous build routed these results through the thread-membership
     * filter, which rejected every ancestor (it is not "in the thread" until
     * it has been fetched), so parents only ever loaded from the local cache.
     */
    private suspend fun fetchById(id: String, hints: List<String> = emptyList()): Event? {
        fetchOnce(id, relays = null)?.let { return it }
        val torEnabled = appSettings.torEnabled.value
        val existingLower = pool.relayUrls().map { RelayPool.normalize(it).lowercase() }.toSet()
        val acquiredLower = acquiredRelays.map { RelayPool.normalize(it).lowercase() }.toSet()
        val candidates = LinkedHashSet<String>()

        // 1. Optional sanitized relay hints (from caller tags + in-memory RelayHintCache)
        if (appSettings.followRelayHintsForMissingNotes.value) {
            (hints + RelayHintCache.get(id)).forEach { raw ->
                val clean = RelayHintPolicy.sanitizeHintUrl(raw, torEnabled) ?: return@forEach
                val lower = clean.lowercase()
                if (lower !in existingLower || lower in acquiredLower) {
                    candidates += clean
                }
            }
        }
        // 2. Optional built-in Fediverse bridge relays (read-only fallback)
        if (appSettings.fediverseBridgeRelaysEnabled.value) {
            Tunables.FEDIVERSE_BRIDGE_RELAYS.forEach { bridge ->
                val clean = RelayHintPolicy.sanitizeHintUrl(bridge, torEnabled) ?: return@forEach
                val lower = clean.lowercase()
                if (lower !in existingLower || lower in acquiredLower) {
                    candidates += clean
                }
            }
        }
        // 3. Built-in default relays (already part of T-Bone's fallback behavior, skipping metadata-only relays)
        Tunables.DEFAULT_RELAYS.forEach { relay ->
            val clean = RelayHintPolicy.sanitizeHintUrl(relay, torEnabled) ?: return@forEach
            val lower = clean.lowercase()
            if (lower !in existingLower || lower in acquiredLower) {
                candidates += clean
            }
        }

        val extra = candidates.take(MAX_FALLBACK_RELAYS)
        if (extra.isEmpty()) return null
        extra.forEach { url ->
            if (acquiredRelays.add(url)) pool.acquireRelay(url)
        }
        val extraTimeout = if (torEnabled) EOSE_TIMEOUT_MS + 6_000L else EOSE_TIMEOUT_MS + 2_500L
        return fetchOnce(id, relays = extra.toSet(), timeoutMs = extraTimeout)
    }

    private suspend fun fetchOnce(id: String, relays: Set<String>?, timeoutMs: Long = EOSE_TIMEOUT_MS): Event? {
        fetchedById[id]?.let { return it }
        val subId = "thread-fetch-" + java.util.UUID.randomUUID().toString()
        fetchSubIds += subId
        activeSubIds += subId
        try {
            pool.subscribeTo(listOf(Filter(ids = listOf(id))), id = subId, label = "thread-fetch", relays = relays)
            withTimeoutOrNull(timeoutMs) {
                merge(flowOf(Unit), eoseSignals).first {
                    fetchedById.containsKey(id) ||
                        (relays == null && eoseCount(subId) >= expectedNoteEose()) ||
                        (relays != null && eoseCount(subId) >= relays.size)
                }
            }
            return fetchedById[id] ?: eventRepository.getById(id)
        } finally {
            fetchSubIds -= subId
            activeSubIds -= subId
            eoseRelays.remove(subId)
            pool.unsubscribe(subId)
        }
    }

    /**
     * Replies are fetched for **every note on the path** (root → focused), not
     * just the tapped note, so nested replies and the notes "in between" all
     * arrive. One REQ carrying several #e filters covers the whole page.
     */
    private fun startThreadSubscriptions(rootId: String, chainIds: List<String>) {
        repliesRequested += chainIds
        repliesRequested += rootId
        val ids = (chainIds + rootId).distinct()
        val repliesSubId = "thread-replies-" + java.util.UUID.randomUUID().toString()
        activeSubIds += repliesSubId
        pool.subscribe(
            listOf(
                // Plain replies and poll replies hang off a lowercase #e tag...
                Filter(
                    eTags = ids,
                    kinds = listOf(EventKind.TEXT_NOTE, EventKind.POLL),
                    limit = THREAD_REPLY_LIMIT,
                ),
                // ...while NIP-22 comments (kind 1111) use #e for their parent
                // and uppercase #E for the thread root. Both are requested so
                // comments from NIP-22 clients appear in the thread.
                Filter(
                    eTags = ids,
                    kinds = listOf(EventKind.COMMENT),
                    limit = THREAD_REPLY_LIMIT,
                ),
                Filter(
                    bigETags = ids,
                    kinds = listOf(EventKind.COMMENT),
                    limit = THREAD_REPLY_LIMIT,
                ),
            ),
            id = repliesSubId,
            label = "thread-replies",
        )
        viewModelScope.launch {
            awaitEose(repliesSubId)
            Timber.d("thread: replies EOSE ($repliesSubId)")
        }
    }

    /** Number of relays we expect an EndOfStoredEvents from. */
    private fun expectedEose(): Int =
        pool.relayStatuses.value.count { it.value == RelayStatus.CONNECTED }.coerceAtLeast(1)

    /** Number of note-capable relays (excluding metadata-only relays like purplepag.es). */
    private fun expectedNoteEose(): Int =
        pool.relayStatuses.value.count { (url, status) ->
            status == RelayStatus.CONNECTED && RelayHintPolicy.isNoteCapableRelay(url)
        }.coerceAtLeast(1)

    private fun eoseCount(subId: String): Int = eoseRelays[subId]?.size ?: 0

    /**
     * Blocks until every connected relay has answered [subId] (or sent nothing
     * at all), capped at [EOSE_TIMEOUT_MS] — never forever.
     */
    private suspend fun awaitEose(subId: String) {
        if (eoseCount(subId) >= expectedEose()) return
        withTimeoutOrNull(EOSE_TIMEOUT_MS) {
            // merge(Unit) re-evaluates the predicate immediately, so an EOSE that
            // landed between the check above and this collection is not missed.
            merge(flowOf(Unit), eoseSignals).first { eoseCount(subId) >= expectedEose() }
        }
    }

    // ── Tree rebuild ──────────────────────────────────────────────────────────

    private fun rebuild() {
        // A fresh snapshot: replies arrive concurrently on the pool collector.
        val snapshot = threadEvents.values.toList()
        val contextEvents = snapshot.associateBy { it.id }
        val blocked = muteRepository.effectiveMuted.value
        val nsfwMode = appSettings.nsfwMode.value
        val hideWords = appSettings.hideWords.value
        // Same rule as the feed: a mute on someone else must not hide a followed
        // author's own reply. `activeAccount` is a cold Flow, so the follow set is
        // cached in a StateFlow rather than read synchronously here.
        val followed = followedPubkeys.value
        val visibleEvents = snapshot.filter {
            !ContentFilter.shouldHide(it, blocked, nsfwMode, hideWords, contextEvents, followed)
        }
        val focused = _uiState.value.focused
        val focusedHidden = focused != null &&
            ContentFilter.shouldHide(focused, blocked, nsfwMode, hideWords, contextEvents, followed)
        val focusedId = focused?.id?.takeUnless { focusedHidden }
        val items = ThreadTree.build(
            rootId = rootId,
            events = visibleEvents,
            focusedId = focusedId,
            scrollTargetId = focusedId,
            // The entire thread is rendered at once: no depth folding and no
            // "show more replies" fan-out cap.
            maxSiblingsInline = Int.MAX_VALUE,
            depthCap = Int.MAX_VALUE,
        )
        _uiState.update { it.copy(items = items, contentFiltered = focusedHidden) }
    }

    // ── Single message collector ──────────────────────────────────────────────

    private suspend fun handlePoolMessage(poolMsg: PoolMessage) {
        val msg = poolMsg.message
        when (msg) {
            is RelayMessage.EventMessage -> {
                val event = msg.event
                if (!event.verify()) return
                when {
                    event.kind == EventKind.METADATA -> profileRepository.processEvent(event)

                    msg.subscriptionId in fetchSubIds -> {
                        // Direct by-id lookups (focused note, ancestors,
                        // repost targets): always accepted and cached.
                        fetchedById[event.id] = event
                        eventRepository.save(event, "")
                        eoseSignals.tryEmit(msg.subscriptionId)
                    }

                    else -> {
                        if (event.kind != EventKind.TEXT_NOTE &&
                            event.kind != EventKind.POLL &&
                            event.kind != EventKind.COMMENT
                        ) {
                            return
                        }
                        if (event.id == _uiState.value.focused?.id) return
                        // Only admit events that belong to this thread.
                        val relatedIds = threadEvents.keys
                        val taggedWith = listOfNotNull(
                            event.parsedTags.replyEventId,
                            event.parsedTags.threadRootEventId,
                        )
                        val inThread = event.id in relatedIds ||
                            taggedWith.any { it == rootId || it in relatedIds }
                        if (!inThread) return

                        // Retain raw events in memory so changing a filter while
                        // this thread is open can reveal them again. [rebuild]
                        // applies the current filters before producing visible rows.
                        val isNew = threadEvents.put(event.id, event) == null
                        viewModelScope.launch { eventRepository.save(event, "") }
                        if (isNew) {
                            rebuild()
                            fetchProfiles(listOf(event))
                            fetchQuotesForEvents(listOf(event))
                            reactionsRepository.subscribeTo(listOf(event.id))
                            repliesRepository.subscribeTo(listOf(event.id))
                            fetchPollsFor(listOf(event))
                            queueReplyLookup(event.id)
                        }
                    }
                }
            }

            is RelayMessage.EndOfStoredEvents -> {
                // One EOSE per relay: record who answered, then release whichever
                // awaitEose() is waiting on this subscription.
                if (msg.subscriptionId !in activeSubIds) return
                val normalized = RelayPool.normalize(poolMsg.relayUrl)
                // Do not let a metadata-only relay (e.g. purplepag.es) satisfy a note lookup EOSE.
                if (msg.subscriptionId in fetchSubIds && RelayHintPolicy.isMetadataOnlyRelay(normalized)) {
                    return
                }
                eoseRelays
                    .getOrPut(msg.subscriptionId) { ConcurrentHashMap.newKeySet() }
                    .add(normalized)
                eoseSignals.tryEmit(msg.subscriptionId)

                if (metadataLeases.contains(msg.subscriptionId) && eoseCount(msg.subscriptionId) >= expectedEose()) {
                    closeMetadata(msg.subscriptionId)
                }
            }

            is RelayMessage.Closed -> {
                if (msg.subscriptionId !in fetchSubIds) return
                val normalized = RelayPool.normalize(poolMsg.relayUrl)
                if (RelayHintPolicy.isMetadataOnlyRelay(normalized)) return
                eoseRelays
                    .getOrPut(msg.subscriptionId) { ConcurrentHashMap.newKeySet() }
                    .add(normalized)
                eoseSignals.tryEmit(msg.subscriptionId)
            }

            else -> Unit
        }
    }

    private fun fetchProfiles(events: List<Event>) {
        val pubkeys = events.map { it.pubkey }.distinct().filter { it.isNotBlank() }
        if (pubkeys.isEmpty()) return
        subscribeMetadata(pubkeys)
    }

    private fun fetchPollsFor(events: List<Event>) {
        pollsRepository.subscribeTo(events.filter { it.kind == EventKind.POLL }.map { it.id })
    }

    /**
     * Resolves the notes referenced by the thread's notes (quote-notes, reposts
     * and inline `nostr:note1…/nevent1…` references) via [QuoteLookupSession].
     */
    fun fetchQuotesForEvents(events: List<Event>) {
        quoteLookup.requestForEvents(events)
    }

    private fun fetchMetadataForAuthors(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        subscribeMetadata(unknown)
    }

    private fun subscribeMetadata(pubkeys: List<String>) {
        pubkeys.distinct().chunked(MAX_FILTER_BATCH).forEach { batch ->
            val id = "thread-metadata-" + java.util.UUID.randomUUID().toString()
            val authors = metadataLeases.claim(id, batch)
            if (authors.isEmpty()) return@forEach
            activeSubIds += id // register before REQ so fast EOSE is not missed
            try {
                pool.subscribe(listOf(Filter(authors = authors, kinds = listOf(EventKind.METADATA))), id = id, label = "thread-metadata")
            } catch (error: Exception) {
                closeMetadata(id)
                throw error
            }
            val timeout = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                kotlinx.coroutines.delay(15_000)
                closeMetadata(id)
            }
            if (metadataLeases.attachTimeout(id, timeout)) timeout.start()
        }
    }

    private fun closeMetadata(id: String) {
        if (!metadataLeases.release(id)) return
        activeSubIds.remove(id)
        eoseRelays.remove(id)
        pool.unsubscribe(id)
    }

    /**
     * Requests the replies of a reply. Clients that don't tag the thread root
     * on every nested reply would otherwise leave deep branches missing.
     * Batched so a burst of incoming replies becomes one REQ.
     */
    private fun queueReplyLookup(id: String) {
        if (id in repliesRequested) return
        pendingReplyLookups += id
        if (replyLookupJob?.isActive == true) return
        replyLookupJob = viewModelScope.launch(Dispatchers.Default) {
            while (pendingReplyLookups.isNotEmpty()) {
                kotlinx.coroutines.delay(REPLY_LOOKUP_DEBOUNCE_MS)
                val drained = pendingReplyLookups.toList()
                pendingReplyLookups.removeAll(drained.toSet())
                val batch = drained.filter { it !in repliesRequested }
                batch.chunked(MAX_FILTER_BATCH).forEach { ids ->
                    startThreadSubscriptions(rootId ?: ids.first(), ids)
                }
            }
        }
    }

    override fun onCleared() {
        quoteLookup.close()
        acquiredRelays.forEach { pool.releaseRelay(it) }
        collectJob?.cancel()
        activeSubIds.forEach { pool.unsubscribe(it) }
    }

    companion object {
        private val MAX_REPOST_CONTENT_BYTES get() = Tunables.MAX_REPOST_CONTENT_BYTES

        /**
         * How long to wait for a relay's EndOfStoredEvents before carrying on
         * with what we have. Without this the old build could wait forever and
         * the parent note simply never appeared.
         */
        const val EOSE_TIMEOUT_MS = 6_000L

        /** Safety bound on the ancestor walk (a malformed thread can't loop). */
        private const val MAX_ANCESTOR_HOPS = 32

        /** Replies requested per thread page. */
        private const val THREAD_REPLY_LIMIT = 200

        /** Ids per `ids` filter — keeps requests inside relay limits. */
        private const val MAX_FILTER_BATCH = 50

        /** Cap on temporary hint/bridge/default relays dialed per missing note. */
        private const val MAX_FALLBACK_RELAYS = 6

        private const val REPLY_LOOKUP_DEBOUNCE_MS = 400L
    }
}
