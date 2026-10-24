package social.tbone.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.db.EventRepository
import social.tbone.feed.SuggestedFollows
import social.tbone.lists.ListType
import social.tbone.lists.MuteListRepository
import social.tbone.lists.OfflineListRepository
import social.tbone.notifications.NotificationsRepository
import social.tbone.reactions.ReactionsRepository
import social.tbone.reactions.PollsRepository
import social.tbone.reactions.RepliesRepository
import social.tbone.settings.AppSettings
import social.tbone.settings.ContentFilter
import social.tbone.Tunables
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.pubkeys
import social.tbone.nostr.quotedEventId
import social.tbone.ui.feed.extractInlineQuoteId
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import social.tbone.profile.ProfileRepository
import social.tbone.updates.UpdateManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import javax.inject.Inject

/**
 * The four feeds, in tab order.
 *
 * [HOME] is your follow list, [CLOCK] is the same follow list anchored N hours
 * in the past, [SUGGESTED] is the curated author set shipped with the app and
 * [GLOBAL] is everything the relays have.
 */
enum class FeedTab { HOME, CLOCK, SUGGESTED, GLOBAL }

data class FeedUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val globalOlderLoading: Boolean = false,
    val globalNewerLoading: Boolean = false,
    val globalNewerAvailable: Int = 0,
    val globalCanLoadOlder: Boolean = false,
    /** Clock feed: epoch second the timeline is anchored at (now − hours). */
    val clockAnchor: Long = 0L,
    /** Suggested feed: how many of the curated accounts produced notes on screen. */
    val suggestedAuthors: Int = 0,
    val suggestedTotal: Int = 0,
    val suggestedLoading: Boolean = false,
)

private enum class GlobalPageKind { OLDER, NEWER }

private class GlobalPageRequest(
    @Volatile var subId: String,
    val kind: GlobalPageKind,
    val expectedRelays: Set<String>,
    val startOldest: Long? = null,
    val startNewest: Long? = null,
    val startVisibleLimit: Int = 0,
    val startKnownIds: Set<String> = emptySet(),
) {
    val events = ConcurrentHashMap<String, Event>()
    /** Events received by the current relay page; used to continue a full 10k page. */
    val pageEvents = ConcurrentHashMap<String, Event>()
    val eoseRelays: MutableSet<String> = ConcurrentHashMap.newKeySet()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val signerFactory: NostrSignerFactory,
    private val reactionsRepository: ReactionsRepository,
    private val repliesRepository: RepliesRepository,
    private val pollsRepository: PollsRepository,
    private val appSettings: AppSettings,
    private val notificationsRepository: NotificationsRepository,
    private val offlineLists: OfflineListRepository,
    private val muteRepository: MuteListRepository,
    private val eventOpenCoordinator: EventOpenCoordinator,
    private val updateManager: UpdateManager,
    private val suggestedFollows: SuggestedFollows,
) : ViewModel() {

    // The suggested feed's roster is known at construction, so the header can
    // show "1,346 accounts" before the first request finishes.
    private val _uiState = MutableStateFlow(FeedUiState(suggestedTotal = suggestedFollows.size))
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    // Sorted set keeps the feed in createdAt-desc order without re-sorting on every insert.
    // Comparator is consistent with event identity: two entries are "equal" only when id matches.
    private val feedEvents = java.util.TreeSet<Event>(
        compareByDescending<Event> { it.createdAt }.thenBy { it.id }
    )
    /** Live Global events are buffered until the user or interval loads them. */
    private val pendingGlobalEvents = ConcurrentHashMap<String, Event>()

    private val _scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToTop: SharedFlow<Unit> = _scrollToTop.asSharedFlow()
    private val _pendingEventScroll = MutableStateFlow<String?>(null)
    val pendingEventScroll: StateFlow<String?> = _pendingEventScroll.asStateFlow()
    @Volatile private var focusedEventId: String? = null

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _currentFeed = MutableStateFlow(FeedTab.HOME)
    val currentFeed: StateFlow<FeedTab> = _currentFeed.asStateFlow()

    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    /**
     * Referenced notes that were asked for and never arrived, so cards show a
     * terminal "not available" row instead of "loading…" forever.
     */
    private val _unresolvedQuoteIds = MutableStateFlow<Set<String>>(emptySet())
    val unresolvedQuoteIds: StateFlow<Set<String>> = _unresolvedQuoteIds.asStateFlow()

    /** Recomputes [unresolvedQuoteIds] from what was requested vs. resolved. */
    private fun refreshUnresolvedQuotes() {
        val resolved = _quotedEvents.value.keys
        _unresolvedQuoteIds.value = requestedQuoteIds.filterTo(mutableSetOf()) { it !in resolved }
    }

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

    /** Unread notification flag — drives the dot on the bell tab. */
    val notifUnread: StateFlow<Boolean> = notificationsRepository.hasUnread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val updateAvailable: StateFlow<Boolean> = updateManager.state
        .map { it.updateAvailable }
        .stateIn(viewModelScope, SharingStarted.Eagerly, updateManager.state.value.updateAvailable)

    val torEnabled: StateFlow<Boolean> = appSettings.torEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val globalAutoLoadEnabled: StateFlow<Boolean> = appSettings.globalAutoLoadEnabled
    val globalAutoLoadIntervalMinutes: StateFlow<Int> = appSettings.globalAutoLoadIntervalMinutes

    fun setGlobalAutoLoadEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setGlobalAutoLoadEnabled(enabled) }
    }

    fun setGlobalAutoLoadIntervalMinutes(minutes: Int) {
        viewModelScope.launch { appSettings.setGlobalAutoLoadIntervalMinutes(minutes) }
    }

    /** Hours back the clock feed is anchored; persisted in DataStore. */
    val clockFeedHours: StateFlow<Int> = appSettings.clockFeedHours

    /**
     * Move the clock feed's anchor. Re-anchors immediately when the clock feed is
     * already on screen, so the list jumps instead of waiting for a tab switch.
     */
    fun setClockFeedHours(hours: Int) {
        val safe = hours.coerceIn(1, 24)
        if (safe == appSettings.clockFeedHours.value) return
        viewModelScope.launch {
            appSettings.setClockFeedHours(safe)
            val account = activeAccount.value ?: return@launch
            if (_currentFeed.value == FeedTab.CLOCK) reanchorClockFeed(account, safe)
        }
    }

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Tracks quote IDs already requested so we don't issue duplicate subscriptions
    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // One-shot subscriptions for fetching quoted/reposted events — handled in collectMessages()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // One-shot subscriptions for fetching author metadata — closed on EOSE
    private val metadataFetchSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var collectJob: Job? = null
    private var feedSubId: String? = null
    @Volatile private var globalOlderSubId: String? = null
    @Volatile private var globalNewerSubId: String? = null
    @Volatile private var globalOlderRequest: GlobalPageRequest? = null
    @Volatile private var globalNewerRequest: GlobalPageRequest? = null
    @Volatile private var globalInitialExpectedRelays: Set<String> = emptySet()
    private val globalInitialEoseRelays: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var globalOlderCursor: Long? = null
    @Volatile private var globalVisibleLimit: Int = GLOBAL_PAGE_SIZE
    // ── Clock feed ────────────────────────────────────────────────────────────
    /** Authors of the clock feed (the follow set, resolved once per load). */
    @Volatile private var clockAuthors: List<String> = emptyList()

    // ── Suggested feed ────────────────────────────────────────────────────────
    /** One subscription per author chunk (relays reject a single 1,300-author REQ). */
    private val suggestedSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Subscription IDs that have reported EOSE at least once, one per chunk. */
    private val suggestedEoseChunks: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var suggestedChunks: Int = 0

    private var followSubId: String? = null
    private var metadataSubId: String? = null
    private var relayListSubId: String? = null
    @Volatile private var followsRelayListSubId: String? = null
    @Volatile private var followsForRelaySelection: List<String> = emptyList()
    private var lastLoadedPubkey: String? = null

    /**
     * Which follow source the HOME feed was last built from: the account's offline
     * (local) follow list, or the relay list. Lets us rebuild the feed when the user
     * flips the switch or edits the local list — and only then.
     */
    private data class FollowMode(val offline: Boolean, val follows: Set<String>)
    @Volatile private var appliedFollowMode: FollowMode? = null
    @Volatile private var appliedForPubkey: String? = null
    private var loadJob: Job? = null

    // outbox model: relay URL → set of follow pubkeys that write there
    private val followRelayMap: ConcurrentHashMap<String, MutableSet<String>> = ConcurrentHashMap()

    // Only process events belonging to current subscriptions — prevents stale events
    // from old subscriptions bleeding into a newly loaded feed.
    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // Events are buffered until the feed is "settled" (follow list processed + EOSE
    // on the expanded subscription), then flushed all at once so the feed snaps into
    // place rather than trickling in one-by-one. After settling, live events stream in.
    @Volatile private var feedSettled = false
    @Volatile private var followsReceived = false
    private val pendingBuffer = ConcurrentLinkedQueue<Event>()

    init {
        viewModelScope.launch {
            eventOpenCoordinator.events.collect { event -> openEventInMainFeed(event) }
        }
        viewModelScope.launch {
            // Only reload the feed when the active pubkey changes, not on every
            // account property update (relays, follows, displayName, etc.).
            // Incremental updates like relay selection and follow list persist
            // to the account but must not restart the feed.
            accountRepository.activeAccount
                .distinctUntilChanged { old, new -> old?.pubkey == new?.pubkey }
                .collect { account ->
                    when {
                        account == null -> clearFeed()
                        _currentFeed.value == FeedTab.GLOBAL -> loadGlobalFeed(account)
                        _currentFeed.value == FeedTab.CLOCK -> loadClockFeed(account)
                        _currentFeed.value == FeedTab.SUGGESTED -> loadSuggestedFeed(account)
                        else -> loadFeed(account)
                    }
                }
        }

        // Offline follow list: rebuild the HOME feed when the switch flips or the local
        // list changes. (Relay-side follow changes never trigger this — no background sync.)
        viewModelScope.launch {
            accountRepository.activeAccount
                .map { it?.pubkey }
                .distinctUntilChanged()
                .flatMapLatest { pk ->
                    if (pk == null) emptyFlow()
                    else offlineLists.state(ListType.FOLLOWS, pk)
                        .map { st -> pk to FollowMode(st.enabled, if (st.enabled) st.pubkeySet() else emptySet()) }
                        .distinctUntilChanged()
                }
                .collect { (pk, mode) ->
                    val applied = appliedFollowMode
                    if (applied == null || appliedForPubkey != pk) return@collect // a load is/will be in charge
                    // Only HOME rebuilds from the offline list; CLOCK resolves its own
                    // authors on load and SUGGESTED never uses the user's follows.
                    if (mode == applied || _currentFeed.value != FeedTab.HOME) return@collect
                    activeAccount.value?.takeIf { it.pubkey == pk }?.let { loadFeed(it, clearEvents = false) }
                }
        }

        // Re-filter the retained raw feed whenever any content rule changes.
        // This ensures hide-word/NSFW edits take effect without a reload and
        // allows notes to reappear if a rule is later removed.
        viewModelScope.launch {
            combine(
                muteRepository.effectiveMuted,
                appSettings.hideNsfw,
                appSettings.hideWords,
            ) { _, _, _ -> Unit }.collect {
                val snapshot = synchronized(feedEvents) { feedEvents.take(feedEventLimit()) }
                _uiState.update { state -> state.copy(events = applyContentFilter(snapshot)) }
            }
        }
    }

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    fun switchFeed(tab: FeedTab) {
        if (_currentFeed.value == tab) {
            _scrollToTop.tryEmit(Unit)
            return
        }
        _currentFeed.update { tab }
        val account = activeAccount.value ?: return
        when (tab) {
            FeedTab.HOME -> loadFeed(account)
            FeedTab.CLOCK -> loadClockFeed(account)
            FeedTab.SUGGESTED -> loadSuggestedFeed(account)
            FeedTab.GLOBAL -> loadGlobalFeed(account)
        }
    }

    /** Fetch the next 100 events strictly older than the oldest Global item. */
    fun loadOlderGlobal() {
        if (_currentFeed.value != FeedTab.GLOBAL || _uiState.value.globalOlderLoading ||
            _uiState.value.globalNewerLoading || !_uiState.value.globalCanLoadOlder ||
            globalVisibleLimit >= MAX_GLOBAL_EVENTS
        ) return
        val prefetchedCount = synchronized(feedEvents) { feedEvents.size }
        if (prefetchedCount > globalVisibleLimit) {
            globalVisibleLimit = (globalVisibleLimit + GLOBAL_PAGE_SIZE).coerceAtMost(MAX_GLOBAL_EVENTS)
            val snapshot = synchronized(feedEvents) { feedEvents.take(feedEventLimit()) }
            _uiState.update { state ->
                state.copy(
                    events = applyContentFilter(snapshot),
                    globalOlderLoading = false,
                    globalCanLoadOlder = globalVisibleLimit < MAX_GLOBAL_EVENTS &&
                        (prefetchedCount > globalVisibleLimit || state.globalCanLoadOlder),
                )
            }
            return
        }
        val oldest = globalOlderCursor ?: synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt } ?: return
        if (oldest <= 0L) return
        val account = activeAccount.value ?: return
        account.relays.ifEmpty { DEFAULT_RELAYS }.forEach { pool.addRelay(it) }

        val subId = UUID.randomUUID().toString().take(8)
        val request = GlobalPageRequest(
            subId = subId,
            kind = GlobalPageKind.OLDER,
            expectedRelays = pool.relayUrls().map(RelayPool::normalize).toSet(),
            startOldest = oldest,
        )
        globalOlderRequest = request
        globalOlderSubId = subId
        activeSubIds.add(subId)
        _uiState.update { it.copy(globalOlderLoading = true) }
        pool.subscribe(
            listOf(
                Filter(
                    kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                    until = oldest - 1,
                    limit = GLOBAL_PAGE_SIZE,
                ),
            ),
            id = subId,
            label = "global-older",
        )
        scheduleGlobalPageTimeout(request)
    }

    /** Fetch all relay-available public events newer than the current Global set. */
    fun loadNewerGlobal() {
        if (_currentFeed.value != FeedTab.GLOBAL || _uiState.value.globalNewerLoading ||
            _uiState.value.globalOlderLoading
        ) return
        val newest = synchronized(feedEvents) { feedEvents.firstOrNull()?.createdAt } ?: return
        // Remember every item already buffered, not only visible items; a newer-page
        // result must not accidentally reveal prefetched older notes and shift the feed.
        val startKnownIds = synchronized(feedEvents) { feedEvents.mapTo(mutableSetOf()) { it.id } }
        val startVisibleLimit = globalVisibleLimit
        val account = activeAccount.value ?: return
        account.relays.ifEmpty { DEFAULT_RELAYS }.forEach { pool.addRelay(it) }

        val subId = UUID.randomUUID().toString().take(8)
        val request = GlobalPageRequest(
            subId = subId,
            kind = GlobalPageKind.NEWER,
            expectedRelays = pool.relayUrls().map(RelayPool::normalize).toSet(),
            startNewest = newest,
            startVisibleLimit = startVisibleLimit,
            startKnownIds = startKnownIds,
        )
        globalNewerRequest = request
        globalNewerSubId = subId
        activeSubIds.add(subId)
        _uiState.update { it.copy(globalNewerLoading = true) }
        pool.subscribe(
            listOf(
                Filter(
                    kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                    since = newest,
                    limit = GLOBAL_NEWER_LIMIT,
                ),
            ),
            id = subId,
            label = "global-newer",
        )
        scheduleGlobalPageTimeout(request)
    }

    private fun scheduleGlobalPageTimeout(request: GlobalPageRequest) {
        val subId = request.subId
        viewModelScope.launch {
            delay(GLOBAL_PAGE_TIMEOUT_MS)
            if (request.subId == subId) finishGlobalPage(request, allowNewerPaging = false)
        }
    }

    private fun handleGlobalPageEvent(request: GlobalPageRequest?, event: Event) {
        if (request == null || _currentFeed.value != FeedTab.GLOBAL) return
        val current = when (request.kind) {
            GlobalPageKind.OLDER -> globalOlderRequest === request
            GlobalPageKind.NEWER -> globalNewerRequest === request
        }
        if (!current || EventKind.isPrivateMessaging(event.kind)) return
        if (request.events.size >= MAX_GLOBAL_EVENTS && !request.events.containsKey(event.id)) return
        val isFeedEvent = when (event.kind) {
            EventKind.TEXT_NOTE -> !isReplyEvent(event)
            EventKind.REPOST, EventKind.POLL -> true
            else -> false
        }
        if (!isFeedEvent) return
        if (request.events.putIfAbsent(event.id, event) == null && request.kind == GlobalPageKind.NEWER) {
            request.pageEvents.putIfAbsent(event.id, event)
        }
    }

    private fun handleGlobalPageEose(request: GlobalPageRequest?, relayUrl: String) {
        if (request == null) return
        val current = when (request.kind) {
            GlobalPageKind.OLDER -> globalOlderRequest === request
            GlobalPageKind.NEWER -> globalNewerRequest === request
        }
        if (!current) return
        request.eoseRelays.add(RelayPool.normalize(relayUrl))
        if (request.expectedRelays.isEmpty() || request.eoseRelays.containsAll(request.expectedRelays)) {
            finishGlobalPage(request, allowNewerPaging = true)
        }
    }

    private fun finishGlobalPage(request: GlobalPageRequest, allowNewerPaging: Boolean) {
        val current = when (request.kind) {
            GlobalPageKind.OLDER -> globalOlderRequest === request
            GlobalPageKind.NEWER -> globalNewerRequest === request
        }
        if (!current) return

        if (allowNewerPaging && request.kind == GlobalPageKind.NEWER &&
            request.pageEvents.size >= GLOBAL_NEWER_LIMIT &&
            request.events.size < MAX_GLOBAL_EVENTS
        ) {
            val startNewest = request.startNewest
            val pageOldest = request.pageEvents.values.minOfOrNull { it.createdAt }
            if (startNewest != null && pageOldest != null && pageOldest > startNewest + 1) {
                unsub(request.subId)
                val nextSubId = UUID.randomUUID().toString().take(8)
                request.subId = nextSubId
                request.pageEvents.clear()
                request.eoseRelays.clear()
                globalNewerSubId = nextSubId
                activeSubIds.add(nextSubId)
                pool.subscribe(
                    listOf(
                        Filter(
                            kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                            since = startNewest,
                            until = pageOldest - 1,
                            limit = GLOBAL_NEWER_LIMIT,
                        ),
                    ),
                    id = nextSubId,
                    label = "global-newer",
                )
                scheduleGlobalPageTimeout(request)
                return
            }
        }

        unsub(request.subId)
        when (request.kind) {
            GlobalPageKind.OLDER -> {
                globalOlderRequest = null
                globalOlderSubId = null
            }
            GlobalPageKind.NEWER -> {
                globalNewerRequest = null
                globalNewerSubId = null
            }
        }

        if (request.kind == GlobalPageKind.NEWER) {
            // Include the live events buffered since the user last loaded; the
            // one-shot relay query remains authoritative for anything not seen
            // by the long-lived subscription.
            val pending = pendingGlobalEvents.values.toList()
            pending.forEach { request.events.putIfAbsent(it.id, it) }
            pending.forEach { pendingGlobalEvents.remove(it.id, it) }
        }
        val incoming = request.events.values.toList()
        if (incoming.isNotEmpty()) synchronized(feedEvents) { feedEvents.addAll(incoming) }

        if (request.kind == GlobalPageKind.OLDER && incoming.isNotEmpty()) {
            globalVisibleLimit = (globalVisibleLimit + GLOBAL_PAGE_SIZE).coerceAtMost(MAX_GLOBAL_EVENTS)
        } else if (request.kind == GlobalPageKind.NEWER) {
            val startNewest = request.startNewest
            if (startNewest != null) {
                val newlyVisible = request.events.values.count {
                    it.createdAt >= startNewest && it.id !in request.startKnownIds
                }
                globalVisibleLimit = maxOf(
                    globalVisibleLimit,
                    (request.startVisibleLimit + newlyVisible).coerceAtMost(MAX_GLOBAL_EVENTS),
                )
            }
        }

        val snapshot = synchronized(feedEvents) { feedEvents.take(feedEventLimit()) }
        if (incoming.isNotEmpty() || request.kind == GlobalPageKind.NEWER) {
            _uiState.update { state -> state.copy(events = applyContentFilter(snapshot)) }
        }
        if (incoming.isNotEmpty()) {
            fetchQuotesForEvents(incoming)
            fetchProfilesForEvents(incoming)
            reactionsRepository.subscribeTo(incoming.map { it.id })
            repliesRepository.subscribeTo(incoming.map { it.id })
            pollsRepository.subscribeTo(incoming.filter { it.kind == EventKind.POLL }.map { it.id })
        }

        if (request.kind == GlobalPageKind.OLDER) {
            val oldestNow = synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt }
            if (oldestNow != null) globalOlderCursor = oldestNow
            val morePrefetched = synchronized(feedEvents) { feedEvents.size > globalVisibleLimit }
            val reachedLimit = globalVisibleLimit >= MAX_GLOBAL_EVENTS
            val foundOlder = oldestNow != null && request.startOldest != null && oldestNow < request.startOldest
            _uiState.update {
                it.copy(
                    globalOlderLoading = false,
                    globalCanLoadOlder = !reachedLimit && (morePrefetched || foundOlder),
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    globalNewerLoading = false,
                    globalNewerAvailable = pendingGlobalEvents.size,
                )
            }
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

    /** Adds a resolved, public event to the visible feed and scrolls directly to it. */
    private fun openEventInMainFeed(event: Event) {
        if (EventKind.isPrivateMessaging(event.kind)) return
        focusedEventId = event.id
        synchronized(feedEvents) { feedEvents.add(event) }
        val visible = listOf(event) + _uiState.value.events.filterNot { it.id == event.id }
        _uiState.update { it.copy(events = applyContentFilter(visible)) }
        fetchQuotesForEvents(listOf(event))
        fetchProfilesForEvents(listOf(event))
        reactionsRepository.subscribeTo(listOf(event.id))
        repliesRepository.subscribeTo(listOf(event.id))
        if (event.kind == EventKind.POLL) pollsRepository.subscribeTo(listOf(event.id))
        _pendingEventScroll.value = event.id
    }

    fun consumePendingEventScroll(eventId: String) {
        if (_pendingEventScroll.value == eventId) _pendingEventScroll.value = null
    }

    fun voteOnPoll(poll: Event, optionIds: List<String>) =
        pollsRepository.vote(poll, optionIds)

    // ── Feed loading ──────────────────────────────────────────────────────────

    /**
     * Applies content filtering (blocked pubkeys, NSFW, hidden words) to a
     * list of events before it reaches the UI.
     */
    private fun applyContentFilter(events: List<social.tbone.nostr.Event>): List<social.tbone.nostr.Event> {
        // Defense in depth: relay filters and event dispatch both restrict the
        // feed, but cached/side-loaded data must also never surface NIP-17 or
        // its NIP-59 wrappers in the public timeline.
        val publicEvents = events.filterNot { EventKind.isPrivateMessaging(it.kind) }
        val blocked = muteRepository.effectiveMuted.value
        val hideNsfw = appSettings.hideNsfw.value
        val hideWords = appSettings.hideWords.value
        val contextEvents = synchronized(feedEvents) { feedEvents.associateBy { it.id } } + _quotedEvents.value
        val filtered = if (blocked.isEmpty() && !hideNsfw && hideWords.isEmpty()) {
            publicEvents
        } else {
            publicEvents.filter {
                !ContentFilter.shouldHide(it, blocked, hideNsfw, hideWords, contextEvents)
            }
        }
        val focused = focusedEventId?.let { id ->
            publicEvents.firstOrNull { it.id == id }
                ?: synchronized(feedEvents) { feedEvents.firstOrNull { it.id == id } }
        }
        val focusedIsAllowed = focused?.let {
            !ContentFilter.shouldHide(it, blocked, hideNsfw, hideWords, contextEvents)
        } == true
        return if (focused != null && focusedIsAllowed && filtered.none { it.id == focused.id }) {
            listOf(focused) + filtered
        } else {
            filtered
        }
    }

    private fun feedEventLimit(): Int =
        if (_currentFeed.value == FeedTab.GLOBAL) globalVisibleLimit.coerceIn(1, MAX_GLOBAL_EVENTS) else MAX_FEED_EVENTS

    fun refresh() {
        val account = activeAccount.value ?: return
        when (_currentFeed.value) {
            FeedTab.GLOBAL -> loadGlobalFeed(account)
            FeedTab.CLOCK -> loadClockFeed(account)
            FeedTab.SUGGESTED -> loadSuggestedFeed(account)
            FeedTab.HOME -> loadFeed(account, clearEvents = false)
        }
    }

    private fun loadFeed(account: Account, clearEvents: Boolean = true) {
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(globalOlderSubId); globalOlderSubId = null
        unsub(globalNewerSubId); globalNewerSubId = null
        globalOlderRequest = null
        globalNewerRequest = null
        globalInitialExpectedRelays = emptySet()
        globalInitialEoseRelays.clear()
        globalOlderCursor = null
        globalVisibleLimit = GLOBAL_PAGE_SIZE
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()

        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        focusedEventId = null
        _pendingEventScroll.value = null
        requestedQuoteIds.clear()
        _unresolvedQuoteIds.value = emptySet()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()
        metadataFetchSubIds.forEach { pool.unsubscribe(it) }
        metadataFetchSubIds.clear()
        if (clearEvents) synchronized(feedEvents) { feedEvents.clear() }
        pendingGlobalEvents.clear()

        _uiState.update {
            if (clearEvents) {
                it.copy(
                    events = emptyList(), isLoading = true, error = null,
                    globalOlderLoading = false, globalNewerLoading = false, globalNewerAvailable = 0, globalCanLoadOlder = false,
                )
            } else {
                it.copy(
                    isLoading = true, error = null,
                    globalOlderLoading = false, globalNewerLoading = false, globalNewerAvailable = 0, globalCanLoadOlder = false,
                )
            }
        }

        loadJob?.cancel()
        appliedFollowMode = null
        appliedForPubkey = null

        // Preload cached events on restart (same account). Skip on account switch to
        // avoid showing stale events from a different account's follow graph.
        val isSameAccount = lastLoadedPubkey == account.pubkey
        lastLoadedPubkey = account.pubkey

        loadJob = viewModelScope.launch {
            // Which follow list drives the feed: the account's offline list (when its switch is
            // on) or the relay list. In offline mode the relay follow list is never even requested.
            val local = offlineLists.snapshot(ListType.FOLLOWS, account.pubkey)
            val offlineMode = local.enabled
            val localFollows = if (offlineMode) local.pubkeySet() else emptySet()
            appliedFollowMode = FollowMode(offlineMode, localFollows)
            appliedForPubkey = account.pubkey

            if (offlineMode) {
                // Drop anything on screen from people no longer in the local list.
                val allowed = localFollows + account.pubkey
                val snapshot = synchronized(feedEvents) {
                    feedEvents.removeAll { it.pubkey !in allowed }
                    feedEvents.take(feedEventLimit())
                }
                _uiState.update { state -> state.copy(events = applyContentFilter(snapshot)) }
            }

            if (isSameAccount) {
                launch {
                    var cached = eventRepository.getRecentFeedEvents(account.pubkey)
                    if (offlineMode) {
                        val allowed = localFollows + account.pubkey
                        cached = cached.filter { it.pubkey in allowed }
                    }
                    if (cached.isNotEmpty()) {
                        val snapshot = synchronized(feedEvents) {
                            feedEvents.addAll(cached)
                            feedEvents.take(feedEventLimit())
                        }
                        _uiState.update { state -> state.copy(events = applyContentFilter(snapshot)) }
                        fetchProfilesForEvents(cached)
                        repliesRepository.subscribeTo(cached.map { it.id })
                    }
                }
            }

            val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
            relays.forEach { pool.addRelay(it) }

            if (offlineMode) {
                // Offline follow list: no kind-3 request at all. The feed is built straight
                // from the local list; "follow list received" is true from the start.
                followsReceived = true
                val followed = local.entries.map { it.pubkey }
                if (followed.isEmpty()) {
                    // Nobody followed locally: just show your own notes.
                    feedSubId = sub(listOf(
                        Filter(
                            authors = listOf(account.pubkey),
                            kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                            limit = 50,
                        )
                    ))
                } else {
                    applyFollowedSet(account.pubkey, followed)
                }
            } else {
                // Own notes first; events buffer until EOSE, then the follow list subscription
                // replaces this and buffers again until its own EOSE — feed appears atomically.
                feedSubId = sub(listOf(
                    Filter(
                        authors = listOf(account.pubkey),
                        kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                        limit = 50,
                    )
                ))
                // Fetch follow list (kind-3) from the relays.
                followSubId = sub(listOf(
                    Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)
                ))
            }

            // Own profile (kind-0) and relay list (kind-10002) in parallel
            metadataSubId = sub(listOf(
                Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.METADATA), limit = 1)
            ))
            relayListSubId = sub(listOf(
                Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.RELAY_LIST), limit = 1)
            ))

            collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

            // Safety net: flush buffer and clear spinner after 15s regardless
            launch {
                delay(15_000)
                if (!feedSettled) {
                    feedSettled = true
                    flushBuffer()
                }
                _uiState.update { if (it.isLoading) it.copy(isLoading = false) else it }
            }
        }
    }

    private fun loadGlobalFeed(account: Account) {
        loadJob?.cancel()
        appliedFollowMode = null
        appliedForPubkey = null
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(globalOlderSubId); globalOlderSubId = null
        unsub(globalNewerSubId); globalNewerSubId = null
        globalOlderRequest = null
        globalNewerRequest = null
        globalInitialExpectedRelays = emptySet()
        globalInitialEoseRelays.clear()
        globalOlderCursor = null
        globalVisibleLimit = GLOBAL_PAGE_SIZE
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()

        feedSettled = false
        pendingBuffer.clear()
        requestedQuoteIds.clear()
        _unresolvedQuoteIds.value = emptySet()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()
        metadataFetchSubIds.forEach { pool.unsubscribe(it) }
        metadataFetchSubIds.clear()
        synchronized(feedEvents) { feedEvents.clear() }
        pendingGlobalEvents.clear()
        focusedEventId = null
        _pendingEventScroll.value = null

        // Reset so returning to HOME skips the cache preload — global events must not
        // bleed into the home feed cache.
        lastLoadedPubkey = null

        _uiState.update {
            it.copy(
                events = emptyList(),
                isLoading = true,
                error = null,
                globalOlderLoading = false,
                globalNewerLoading = false,
                globalNewerAvailable = 0,
                globalCanLoadOlder = false,
            )
        }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }
        globalInitialExpectedRelays = pool.relayUrls().map(RelayPool::normalize).toSet()
        globalInitialEoseRelays.clear()

        feedSubId = sub(listOf(
            Filter(kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL), limit = GLOBAL_PAGE_SIZE)
        ))

        // No follow-list phase — mark followsReceived so EOSE on feedSubId triggers flush
        followsReceived = true

        collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

        viewModelScope.launch {
            delay(GLOBAL_PAGE_TIMEOUT_MS)
            settleGlobalInitial()
        }
    }

    /**
     * Resets every subscription, buffer and cursor shared by the four feeds.
     *
     * HOME and GLOBAL have their own (older, subtly different) reset blocks; this
     * helper exists so the clock and suggested loaders share exactly one — a
     * missed field here would leak events between feeds.
     */
    private fun resetFeedState(clearEvents: Boolean = true) {
        loadJob?.cancel()
        appliedFollowMode = null
        appliedForPubkey = null
        collectJob?.cancel()
        activeSubIds.clear()

        unsub(feedSubId); feedSubId = null
        unsub(globalOlderSubId); globalOlderSubId = null
        unsub(globalNewerSubId); globalNewerSubId = null
        globalOlderRequest = null
        globalNewerRequest = null
        globalInitialExpectedRelays = emptySet()
        globalInitialEoseRelays.clear()
        globalOlderCursor = null
        globalVisibleLimit = GLOBAL_PAGE_SIZE
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()

        clockAuthors = emptyList()

        suggestedSubIds.forEach { pool.unsubscribe(it) }
        suggestedSubIds.clear()
        suggestedEoseChunks.clear()
        suggestedChunks = 0

        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        focusedEventId = null
        _pendingEventScroll.value = null
        requestedQuoteIds.clear()
        _unresolvedQuoteIds.value = emptySet()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()
        metadataFetchSubIds.forEach { pool.unsubscribe(it) }
        metadataFetchSubIds.clear()
        if (clearEvents) synchronized(feedEvents) { feedEvents.clear() }
        pendingGlobalEvents.clear()
    }

    /**
     * The clock feed: your follow timeline, frozen N hours ago.
     *
     * `until` is the anchor (now − [AppSettings.clockFeedHours]); paging walks
     * further back from there. Nothing newer than the anchor is allowed in, so
     * the window cannot creep forward while the user is reading.
     */
    private fun loadClockFeed(account: Account) {
        resetFeedState()
        lastLoadedPubkey = null
        val hours = appSettings.clockFeedHours.value.coerceIn(1, 24)
        val anchor = nowSeconds() - hours * 3600L

        _uiState.update {
            it.copy(
                events = emptyList(),
                isLoading = true,
                error = null,
                clockAnchor = anchor,
                globalOlderLoading = false,
                globalNewerLoading = false,
                globalNewerAvailable = 0,
                globalCanLoadOlder = false,
            )
        }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }

        collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

        loadJob = viewModelScope.launch {
            // Same follow source as HOME: the offline list wins when it is switched on.
            val local = offlineLists.snapshot(ListType.FOLLOWS, account.pubkey)
            val follows = if (local.enabled) local.pubkeySet().toList()
                else account.follows.ifEmpty { fetchFollowsOnce(account) }
            val authors = (follows + account.pubkey).distinct()
            clockAuthors = authors

            followsReceived = true
            feedSubId = sub(
                listOf(
                    Filter(
                        authors = authors,
                        kinds = FEED_KINDS,
                        until = anchor,
                        limit = CLOCK_PAGE_SIZE,
                    )
                ),
                label = "clock",
            )

            viewModelScope.launch {
                delay(SETTLE_TIMEOUT_MS)
                settleWindowFeed()
            }
        }
    }

    /**
     * Moves the clock window in place, without blanking the screen.
     *
     * Rewinding (6h -> 24h) keeps every loaded note and quietly tops up the new,
     * earlier stretch. Fast-forwarding (24h -> 6h) drops the notes that are now
     * "too old to exist yet" and streams the newer ones in. Either way the list
     * never flashes empty, which is what makes the hour strip feel instant.
     */
    private fun reanchorClockFeed(account: Account, hours: Int) {
        val anchor = nowSeconds() - hours * 3600L
        val kept = synchronized(feedEvents) {
            feedEvents.removeAll { it.createdAt >= anchor }
            feedEvents.toList()
        }
        _uiState.update {
            it.copy(
                events = applyContentFilter(kept),
                clockAnchor = anchor,
                isLoading = kept.isEmpty(),
                error = null,
            )
        }

        // Only the clock subscription is replaced; quote/profile/reaction subs stay.
        unsub(feedSubId); feedSubId = null
        followsReceived = true
        feedSettled = true
        if (clockAuthors.isEmpty()) {
            loadClockFeed(account)
            return
        }

        account.relays.ifEmpty { DEFAULT_RELAYS }.forEach { pool.addRelay(it) }
        feedSubId = sub(
            listOf(
                Filter(
                    authors = clockAuthors,
                    kinds = FEED_KINDS,
                    until = anchor,
                    limit = CLOCK_PAGE_SIZE,
                )
            ),
            label = "clock",
        )
        viewModelScope.launch {
            delay(SETTLE_TIMEOUT_MS)
            if (feedSubId != null) _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * The suggested feed: a fixed, curated author set shipped with the app.
     *
     * The set is ~1,300 accounts, so it is queried as several sliced REQs that
     * merge into a single timeline. There is no "older" paging on purpose — this
     * feed is a cross-section of what those accounts posted recently.
     */
    private fun loadSuggestedFeed(account: Account) {
        resetFeedState()
        lastLoadedPubkey = null
        val chunks = suggestedFollows.chunks()
        suggestedChunks = chunks.size

        _uiState.update {
            it.copy(
                events = emptyList(),
                isLoading = true,
                error = null,
                clockAnchor = 0L,
                globalOlderLoading = false,
                globalNewerLoading = false,
                globalNewerAvailable = 0,
                globalCanLoadOlder = false,
                suggestedLoading = true,
                suggestedTotal = suggestedFollows.size,
                suggestedAuthors = 0,
            )
        }

        val relays = account.relays.ifEmpty { DEFAULT_RELAYS }
        relays.forEach { pool.addRelay(it) }
        suggestedEoseChunks.clear()

        followsReceived = true
        collectJob = viewModelScope.launch(Dispatchers.Default) { collectMessages() }

        if (chunks.isEmpty()) {
            feedSettled = true
            _uiState.update { it.copy(isLoading = false, suggestedLoading = false) }
            return
        }

        // Enough per chunk to fill MAX_FEED_EVENTS across all of them, capped so a
        // single chunk cannot dominate the merged timeline.
        val perChunk = ((MAX_FEED_EVENTS.toFloat() / chunks.size).toInt() + 8).coerceIn(12, 40)
        chunks.forEach { chunk ->
            val id = sub(
                listOf(Filter(authors = chunk, kinds = FEED_KINDS, limit = perChunk)),
                label = "suggested",
            )
            suggestedSubIds.add(id)
        }

        viewModelScope.launch {
            delay(SUGGESTED_TIMEOUT_MS)
            settleSuggested()
        }
    }

    /** Flushes once every chunk has been answered (or the timeout wins). */
    private fun settleSuggested() {
        if (feedSettled) return
        feedSettled = true
        // One-shot feed: nine merged slices are enough, no need to keep nine
        // live subscriptions open (and re-flushing) for the rest of the session.
        suggestedSubIds.forEach { pool.unsubscribe(it) }
        suggestedSubIds.clear()
        flushBuffer()
        _uiState.update {
            it.copy(
                isLoading = false,
                suggestedLoading = false,
                suggestedAuthors = synchronized(feedEvents) { feedEvents.map { e -> e.pubkey }.toSet().size },
            )
        }
    }

    /** Shared settle for the clock feed (single subscription + EOSE/timeout). */
    private fun settleWindowFeed() {
        if (feedSettled) return
        feedSettled = true
        flushBuffer()
        _uiState.update { it.copy(isLoading = false) }
    }

    /**
     * One-shot kind-3 fetch for accounts whose follow list is not cached locally.
     * Resolves the first event that arrives, or an empty list after [timeoutMs].
     */
    private suspend fun fetchFollowsOnce(account: Account, timeoutMs: Long = 8_000): List<String> {
        val cached = account.follows
        if (cached.isNotEmpty()) return cached
        val result = kotlinx.coroutines.CompletableDeferred<List<String>>()
        val id = pool.subscribe(
            listOf(Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)),
            label = "clock-follows",
        )
        val job = viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                if (message is RelayMessage.EventMessage &&
                    message.subscriptionId == id &&
                    message.event.kind == EventKind.FOLLOW_LIST &&
                    message.event.verify()
                ) {
                    result.complete(message.event.parsedTags.pubkeys)
                }
            }
        }
        val follows = withTimeoutOrNull(timeoutMs) { result.await() } ?: emptyList()
        job.cancel()
        pool.unsubscribe(id)
        return follows
    }

    private fun settleGlobalInitial() {
        if (!feedSettled) {
            feedSettled = true
            flushBuffer()
        }
        val oldest = synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt }
        globalOlderCursor = oldest
        val canLoadOlder = synchronized(feedEvents) { feedEvents.size >= GLOBAL_PAGE_SIZE }
        _uiState.update {
            it.copy(isLoading = false, globalCanLoadOlder = canLoadOlder)
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /** Subscribe and track the ID before sending REQ so fast relays cannot beat registration. */
    private fun sub(filters: List<Filter>, label: String = "feed"): String {
        val id = UUID.randomUUID().toString().take(8)
        activeSubIds.add(id)
        pool.subscribe(filters, id = id, label = label)
        return id
    }

    /** Unsubscribe and stop tracking the ID. */
    private fun unsub(id: String?) {
        id ?: return
        activeSubIds.remove(id)
        pool.unsubscribe(id)
    }

    private suspend fun collectMessages() {
        pool.messages.collect { poolMessage ->
            when (val msg = poolMessage.message) {
                is RelayMessage.EventMessage -> {
                    when {
                        msg.subscriptionId == globalOlderSubId -> {
                            if (msg.event.verify()) handleGlobalPageEvent(globalOlderRequest, msg.event)
                        }
                        msg.subscriptionId == globalNewerSubId -> {
                            if (msg.event.verify()) handleGlobalPageEvent(globalNewerRequest, msg.event)
                        }
                        msg.subscriptionId in activeSubIds -> {
                            if (msg.event.verify()) handleEvent(msg.event)
                        }
                        msg.subscriptionId in quoteSubIds -> {
                            if (msg.event.verify()) handleQuoteEvent(msg.event)
                        }
                    }
                }
                is RelayMessage.EndOfStoredEvents -> {
                    when {
                        msg.subscriptionId in quoteSubIds -> {
                            // First EOSE = fastest relay only. Keep listening so a
                            // slower relay holding the note can still deliver it.
                            val sid = msg.subscriptionId
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(QUOTE_GRACE_MS)
                                if (quoteSubIds.remove(sid)) pool.unsubscribe(sid)
                                // Lookup finished: whatever is still missing is
                                // genuinely unavailable, not "loading".
                                refreshUnresolvedQuotes()
                            }
                        }
                        msg.subscriptionId in metadataFetchSubIds -> {
                            metadataFetchSubIds.remove(msg.subscriptionId)
                            unsub(msg.subscriptionId)
                        }
                        msg.subscriptionId in suggestedSubIds -> {
                            // Settle once every chunk has been answered by at least one
                            // relay, so the merged timeline appears in one go instead of
                            // chunk by chunk.
                            suggestedEoseChunks.add(msg.subscriptionId)
                            if (suggestedChunks > 0 && suggestedEoseChunks.size >= suggestedChunks) {
                                settleSuggested()
                            }
                        }
                        msg.subscriptionId == globalOlderSubId -> {
                            handleGlobalPageEose(globalOlderRequest, poolMessage.relayUrl)
                        }
                        msg.subscriptionId == globalNewerSubId -> {
                            handleGlobalPageEose(globalNewerRequest, poolMessage.relayUrl)
                        }
                        // The clock feed's own subscription settles through its own
                        // path; the generic branch below only knows about Global paging.
                        msg.subscriptionId == feedSubId && _currentFeed.value == FeedTab.CLOCK -> {
                            settleWindowFeed()
                        }
                        msg.subscriptionId == feedSubId && _currentFeed.value == FeedTab.GLOBAL -> {
                            globalInitialEoseRelays.add(RelayPool.normalize(poolMessage.relayUrl))
                            if (globalInitialExpectedRelays.isEmpty() ||
                                globalInitialEoseRelays.containsAll(globalInitialExpectedRelays)
                            ) settleGlobalInitial()
                        }
                        msg.subscriptionId !in activeSubIds -> return@collect
                        msg.subscriptionId == followsRelayListSubId -> {
                            unsub(followsRelayListSubId); followsRelayListSubId = null
                            val follows = followsForRelaySelection
                            if (follows.isNotEmpty()) selectAndApplyRelays(follows)
                        }
                        // Expanded feed (after follow list) is ready — show everything.
                        msg.subscriptionId == feedSubId && followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            val canLoadOlder = _currentFeed.value == FeedTab.GLOBAL &&
                                synchronized(feedEvents) { feedEvents.size >= GLOBAL_PAGE_SIZE }
                            _uiState.update {
                                it.copy(isLoading = false, globalCanLoadOlder = canLoadOlder)
                            }
                        }
                        // Follow subscription returned nothing (user has no follows) —
                        // fall back to showing own notes.
                        msg.subscriptionId == followSubId && !followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            _uiState.update { it.copy(isLoading = false) }
                        }
                        else -> _uiState.update { it.copy(isLoading = false) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun handleQuoteEvent(event: Event) {
        if (EventKind.isPrivateMessaging(event.kind)) return
        _quotedEvents.update { it + (event.id to event) }
        refreshUnresolvedQuotes()
        viewModelScope.launch { eventRepository.save(event, "") }
        fetchMetadataForAuthors(listOf(event.pubkey))
        // A reposted/quoted note may itself quote another note.
        if (event.kind != EventKind.REPOST) fetchQuotesForEvents(listOf(event))
    }

    private fun isReplyEvent(event: Event): Boolean {
        val eTags = event.parsedTags.filter { it.name == "e" }
        return eTags.size > 1 || eTags.any { tag ->
            val marker = tag.value(3) ?: tag.value(2)
            marker == "reply" || marker == "root"
        }
    }

    private fun handleEvent(event: Event) {
        if (EventKind.isPrivateMessaging(event.kind)) return
        when (event.kind) {
            EventKind.FOLLOW_LIST -> expandFeedToFollows(event)
            EventKind.METADATA    -> {
                profileRepository.processEvent(event)
                val selfPubkey = activeAccount.value?.pubkey ?: return
                if (event.pubkey == selfPubkey) {
                    val content = ProfileContent.parse(event.content) ?: return
                    viewModelScope.launch {
                        accountRepository.updateAccount(selfPubkey) {
                            it.copy(displayName = content.bestName, pictureUrl = content.picture)
                        }
                    }
                }
            }
            EventKind.RELAY_LIST  -> {
                val selfPubkey = activeAccount.value?.pubkey ?: return
                if (event.pubkey == selfPubkey) handleRelayList(event)
                else handleFollowRelayList(event)
            }
            EventKind.TEXT_NOTE   -> {
                if (isReplyEvent(event)) return
                addToFeed(event)
            }
            EventKind.REPOST      -> addToFeed(event)
            EventKind.POLL        -> addToFeed(event)
        }
    }

    /**
     * Called when the follow list (kind-3) arrives.
     * Replaces the narrow own-notes subscription with a full home feed
     * and kicks off a metadata fetch for all followed pubkeys.
     */
    private fun expandFeedToFollows(event: Event) {
        val followed = event.parsedTags.pubkeys
        if (followed.isEmpty()) return

        unsub(followSubId); followSubId = null

        val selfPubkey = activeAccount.value?.pubkey ?: return

        applyFollowedSet(selfPubkey, followed)

        // Persist follow list with full relay hints so toggleFollow can preserve them.
        // selfPubkey is captured from above — stable, never changes for the active account.
        val entries = event.parsedTags
            .filter { it.name == "p" }
            .mapNotNull { tag ->
                val pk = tag.value(1) ?: return@mapNotNull null
                social.tbone.account.FollowEntry(
                    pubkey = pk,
                    relay = tag.value(2) ?: "",
                    petname = tag.value(3) ?: "",
                )
            }
        viewModelScope.launch {
            accountRepository.updateAccount(selfPubkey) {
                it.copy(follows = followed, followEntries = entries)
            }
        }
    }

    /**
     * Builds the full home feed for [followed] (+ yourself): replaces the narrow
     * own-notes subscription, prefetches profiles, and runs outbox relay selection.
     *
     * Source-agnostic: the follow set comes either from the relay kind-3 event
     * ([expandFeedToFollows]) or from the account's offline list (see [loadFeed]).
     * This function never writes the follow list anywhere.
     */
    private fun applyFollowedSet(selfPubkey: String, followed: List<String>) {
        val allPubkeys = (followed + selfPubkey).distinct()

        // Follow list known — expand to full feed and wait for its EOSE.
        followsReceived = true
        unsub(feedSubId)
        feedSettled = false
        feedSubId = sub(listOf(
            Filter(
                authors = allPubkeys,
                kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
                limit = 200,
            )
        ))

        // Eagerly fetch profile metadata for everyone we follow in parallel with
        // the feed events, so names render as soon as the feed appears instead of
        // lagging behind each note. Cached profiles skip the network entirely.
        fetchMetadataForAuthors(allPubkeys)

        // Outbox model: fetch kind-10002 for all follows to discover their write relays,
        // then run greedy set cover to select the ~7 relays that cover the most follows.
        unsub(followsRelayListSubId)
        followRelayMap.clear()
        followsForRelaySelection = followed
        followsRelayListSubId = sub(listOf(
            Filter(authors = followed, kinds = listOf(EventKind.RELAY_LIST))
        ))
        viewModelScope.launch {
            delay(5_000)
            if (followsRelayListSubId != null) {
                unsub(followsRelayListSubId); followsRelayListSubId = null
                selectAndApplyRelays(followed)
            }
        }
    }

    /**
     * NIP-65: seed the relay list from the network on first login.
     *
     * If the user already has a local relay list, the network event is ignored —
     * local changes must not be clobbered by a stale Nostr-published snapshot.
     * Without a publish path, the two lists can diverge; local always wins.
     */
    private fun handleRelayList(event: Event) {
        val readRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "read") url else null
            }
        if (readRelays.isEmpty()) return

        unsub(relayListSubId); relayListSubId = null

        // Quick pre-check with the cached StateFlow value — fine here since pubkey is stable
        // and the transform below re-checks atomically inside the DataStore edit mutex.
        val account = activeAccount.value ?: return
        if (account.relays.isNotEmpty()) return
        val selfPubkey = account.pubkey
        // Cap on ingest: a NIP-65 list with a dozen+ entries shouldn't translate to a
        // dozen+ live connections. selectAndApplyRelays caps again on the combined set.
        val normalized = readRelays.map { RelayPool.normalize(it) }.distinct()
            .take(Tunables.MAX_TOTAL_RELAYS)
        viewModelScope.launch {
            var didSeed = false
            accountRepository.updateAccount(selfPubkey) { current ->
                if (current.relays.isEmpty()) { didSeed = true; current.copy(relays = normalized) }
                else current
            }
            if (didSeed) normalized.forEach { pool.addRelay(it) }
        }
    }

    private fun addToFeed(event: Event) {
        if (EventKind.isPrivateMessaging(event.kind)) return
        // Only persist home feed events — global/clock/suggested are views and must
        // not pollute the cache that getRecentFeedEvents returns on next home load.
        if (_currentFeed.value == FeedTab.HOME) {
            val accountPubkey = activeAccount.value?.pubkey ?: return
            viewModelScope.launch { eventRepository.save(event, accountPubkey) }
        }
        // The clock feed is anchored in the past: a note published after the anchor
        // belongs to "now", so it must never slide into the window while reading.
        if (_currentFeed.value == FeedTab.CLOCK && event.createdAt >= _uiState.value.clockAnchor) return
        if (!feedSettled) {
            pendingBuffer.add(event)
            return
        }
        if (_currentFeed.value == FeedTab.GLOBAL) {
            val alreadyLoaded = synchronized(feedEvents) { feedEvents.contains(event) }
            if (!alreadyLoaded) {
                pendingGlobalEvents.putIfAbsent(event.id, event)
                if (pendingGlobalEvents.size > MAX_GLOBAL_EVENTS) {
                    pendingGlobalEvents.values.minByOrNull { it.createdAt }?.let { oldest ->
                        pendingGlobalEvents.remove(oldest.id, oldest)
                    }
                }
                _uiState.update { it.copy(globalNewerAvailable = pendingGlobalEvents.size) }
            }
            // Don't mutate the visible list for each live event. Loading them in
            // a batch prevents the bottom item from being repeatedly evicted and
            // keeps the Global tab anchored while someone is reading older notes.
            return
        }
        val snapshot = synchronized(feedEvents) {
            feedEvents.add(event)
            feedEvents.take(feedEventLimit())
        }
        if (_currentFeed.value == FeedTab.GLOBAL) {
            globalOlderCursor = synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt }
        }
        _uiState.update { state ->
            state.copy(
                events = applyContentFilter(snapshot),
                globalCanLoadOlder = if (_currentFeed.value == FeedTab.GLOBAL && snapshot.size >= GLOBAL_PAGE_SIZE) {
                    true
                } else state.globalCanLoadOlder,
            )
        }
        fetchQuotesForEvents(listOf(event))
        fetchProfilesForEvents(listOf(event))
        reactionsRepository.subscribeTo(listOf(event.id))
        repliesRepository.subscribeTo(listOf(event.id))
        if (event.kind == EventKind.POLL) pollsRepository.subscribeTo(listOf(event.id))
    }

    /** Flush buffered events into the UI all at once, sorted by recency, then scroll to top. */
    private fun flushBuffer() {
        val events = pendingBuffer.toList().also { pendingBuffer.clear() }
        if (events.isEmpty()) return
        val snapshot = synchronized(feedEvents) {
            feedEvents.addAll(events)
            feedEvents.take(feedEventLimit())
        }
        _uiState.update { state -> state.copy(events = applyContentFilter(snapshot)) }
        _scrollToTop.tryEmit(Unit)
        fetchQuotesForEvents(events)
        fetchProfilesForEvents(events)
        reactionsRepository.subscribeTo(events.map { it.id })
        repliesRepository.subscribeTo(events.map { it.id })
        pollsRepository.subscribeTo(events.filter { it.kind == EventKind.POLL }.map { it.id })
    }

    private fun fetchQuotesForEvents(events: List<Event>) {
        val toResolve = mutableListOf<String>()

        for (event in events) {
            when (event.kind) {
                EventKind.REPOST -> {
                    // Kind-6: guard against malicious oversized content before parsing.
                    val embedded = if (event.content.length > MAX_REPOST_CONTENT_BYTES) null
                    else runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        refreshUnresolvedQuotes()
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                        fetchMetadataForAuthors(listOf(embedded.pubkey))
                        if (embedded.kind != EventKind.REPOST) fetchQuotesForEvents(listOf(embedded))
                    } else {
                        // Fall back to fetching by e tag
                        val refId = event.parsedTags.firstOrNull { it.name == "e" }?.value()
                        if (refId != null && refId !in requestedQuoteIds) toResolve.add(refId)
                    }
                }
                EventKind.TEXT_NOTE, EventKind.COMMENT, EventKind.POLL -> {
                    val qId = event.parsedTags.quotedEventId
                        ?: extractInlineQuoteId(event.content)
                    if (qId != null && qId !in requestedQuoteIds) toResolve.add(qId)
                }
            }
        }

        val missing = toResolve.distinct().filter { it !in _quotedEvents.value }
        if (missing.isEmpty()) return
        missing.forEach { requestedQuoteIds.add(it) }

        viewModelScope.launch {
            val cached = eventRepository.getByIds(missing)
                .filterNot { EventKind.isPrivateMessaging(it.kind) }
                .associateBy { it.id }
            if (cached.isNotEmpty()) {
                _quotedEvents.update { it + cached }
                refreshUnresolvedQuotes()
                fetchMetadataForAuthors(cached.values.map { it.pubkey })
            }
            val stillMissing = missing.filter { it !in cached }
            if (stillMissing.isEmpty()) return@launch

            // Kind-agnostic on purpose: a quoted poll or repost was never
            // resolved before, so the reference stayed as a dead link.
            val subId = pool.subscribe(
                listOf(Filter(ids = stillMissing)),
                label = "quote",
            )
            quoteSubIds.add(subId)
        }
    }

    private fun fetchMetadataForAuthors(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        val id = sub(listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))), label = "metadata")
        metadataFetchSubIds.add(id)
    }

    /** Fetch kind-0 for note authors and any pubkeys mentioned via p-tags. */
    private fun fetchProfilesForEvents(events: List<Event>) {
        val pubkeys = events.flatMap { event ->
            buildList {
                add(event.pubkey)
                if (event.kind == EventKind.TEXT_NOTE) addAll(event.parsedTags.pubkeys)
            }
        }
        fetchMetadataForAuthors(pubkeys)
    }

    private fun clearFeed() {
        loadJob?.cancel()
        appliedFollowMode = null
        appliedForPubkey = null
        collectJob?.cancel()
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(globalOlderSubId); globalOlderSubId = null
        unsub(globalNewerSubId); globalNewerSubId = null
        globalOlderRequest = null
        globalNewerRequest = null
        globalInitialExpectedRelays = emptySet()
        globalInitialEoseRelays.clear()
        globalOlderCursor = null
        globalVisibleLimit = GLOBAL_PAGE_SIZE
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()
        quoteSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.clear()
        metadataFetchSubIds.forEach { pool.unsubscribe(it) }
        metadataFetchSubIds.clear()
        synchronized(feedEvents) { feedEvents.clear() }
        pendingGlobalEvents.clear()
        focusedEventId = null
        _pendingEventScroll.value = null
        _uiState.update { FeedUiState(isLoading = false) }
    }

    /**
     * NIP-65: collect write relays for a follow so we can run outbox relay selection.
     */
    private fun handleFollowRelayList(event: Event) {
        val writeRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "write") url else null
            }
        writeRelays.forEach { relay ->
            followRelayMap.getOrPut(relay) { ConcurrentHashMap.newKeySet() }.add(event.pubkey)
        }
    }

    /**
     * Greedy set cover: selects up to [maxRelays] relay URLs that collectively cover
     * the most follow pubkeys, favouring relays with the broadest reach first.
     */
    private fun greedyRelaySelection(follows: List<String>, maxRelays: Int = MAX_OUTBOX_RELAYS): List<String> {
        if (followRelayMap.isEmpty()) return emptyList()
        val uncovered = follows.toMutableSet()
        val selected = mutableListOf<String>()
        while (uncovered.isNotEmpty() && selected.size < maxRelays) {
            val best = followRelayMap.entries
                .filter { it.key !in selected }
                .maxByOrNull { entry -> entry.value.count { it in uncovered } }
                ?: break
            if (best.value.none { it in uncovered }) break
            selected.add(best.key)
            uncovered.removeAll(best.value)
        }
        Timber.d("Relay selection: ${selected.size} relays cover ${follows.size - uncovered.size}/${follows.size} follows")
        return selected
    }

    /**
     * Applies the greedy relay selection: adds new relays, removes stale ones,
     * and persists the result to the account.
     */
    private fun selectAndApplyRelays(follows: List<String>) {
        val selfPubkey = activeAccount.value?.pubkey ?: return
        val outbox = greedyRelaySelection(follows)
        viewModelScope.launch {
            // The transform runs inside DataStore's edit mutex, so it always sees the
            // freshest relay list — no stale-snapshot race with RelayManagementViewModel.
            var newRelays = emptyList<String>()
            accountRepository.updateAccount(selfPubkey) { account ->
                // DEFAULT_RELAYS are a fallback only when the account has no relays at all.
                val base = account.relays.ifEmpty { DEFAULT_RELAYS }
                // Preserve all user-configured relays (base), then fill remaining slots
                // up to MAX_TOTAL_RELAYS with outbox picks. A previous version applied
                // .take() to (base + outbox), which silently dropped a manually-added
                // relay (e.g. user adds Citrine as the 9th entry → cap chops it).
                val baseNorm = base.map { RelayPool.normalize(it) }.distinct()
                val remaining = (Tunables.MAX_TOTAL_RELAYS - baseNorm.size).coerceAtLeast(0)
                val outboxFill = outbox
                    .map { RelayPool.normalize(it) }
                    .distinct()
                    .filterNot { it in baseNorm }
                    .take(remaining)
                newRelays = baseNorm + outboxFill
                account.copy(relays = newRelays)
            }
            val current = pool.relayUrls()
            newRelays.forEach { pool.addRelay(it) }
            (current - newRelays.toSet()).forEach { pool.removeRelay(it) }
            Timber.d("Applied relay set (${newRelays.size}): $newRelays")
        }
    }

    companion object {
        /** Kinds every timeline feed shows: notes, reposts and polls. */
        private val FEED_KINDS = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL)
        /** Page size for the clock feed's single page (there is no older-paging). */
        private const val CLOCK_PAGE_SIZE = 100
        /** How long HOME/CLOCK wait for EOSE before showing what arrived. */
        private const val SETTLE_TIMEOUT_MS = 12_000L
        /** The suggested feed fans out over ~9 chunks; give the slowest one time. */
        private const val SUGGESTED_TIMEOUT_MS = 14_000L

        private const val GLOBAL_PAGE_SIZE = 100
        private const val GLOBAL_NEWER_LIMIT = 10_000
        private const val MAX_GLOBAL_EVENTS = 50_000
        private const val GLOBAL_PAGE_TIMEOUT_MS = 15_000L
        private val MAX_FEED_EVENTS get() = Tunables.MAX_FEED_EVENTS
        private val MAX_OUTBOX_RELAYS get() = Tunables.MAX_OUTBOX_RELAYS
        private val MAX_REPOST_CONTENT_BYTES get() = Tunables.MAX_REPOST_CONTENT_BYTES
        val DEFAULT_RELAYS get() = Tunables.DEFAULT_RELAYS
    }
}

/** How long a quote lookup stays open after the first relay EOSE. */
private const val QUOTE_GRACE_MS = 8_000L
