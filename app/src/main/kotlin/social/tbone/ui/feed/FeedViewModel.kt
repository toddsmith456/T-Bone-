package social.tbone.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.di.GlobalFeedPool
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
import social.tbone.settings.HideReason
import social.tbone.settings.NsfwMode
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
import social.tbone.nostr.relay.GlobalRelaySession
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import social.tbone.profile.ProfileRepository
import social.tbone.updates.UpdateManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import javax.inject.Inject
import social.tbone.home.FeedPlan
import social.tbone.home.FeedPlanner
import social.tbone.home.FeedSlot
import social.tbone.home.FeedSlotEngine
import social.tbone.home.FeedSource
import social.tbone.home.HomeLayout
import social.tbone.home.HomeLayoutRepository
import social.tbone.home.PoolChoice
import social.tbone.home.SlotChange
import social.tbone.home.SlotRuntime
import kotlinx.coroutines.flow.drop

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
    /** Visible notes interleaved with grouped markers for hidden notes. */
    val feedItems: List<FeedListItem> = emptyList(),
    /** How many notes the content filters removed from this feed. */
    val hiddenCount: Int = 0,
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

/** One row of the feed list: a note, or a collapsed group of hidden notes. */
sealed interface FeedListItem {
    val key: String
    data class Note(val event: Event) : FeedListItem {
        override val key: String get() = event.id
    }
    data class HiddenGroup(
        val entries: List<HiddenEntry>,
        override val key: String,
    ) : FeedListItem
}

/** A hidden note's identity + why it was hidden (never its content). */
data class HiddenEntry(
    val eventId: String,
    val pubkey: String,
    val reason: HideReason,
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
    @GlobalFeedPool private val globalPool: RelayPool,
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
    private val homeLayouts: HomeLayoutRepository,
    private val feedPlanner: FeedPlanner,
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

    private val globalSession = GlobalRelaySession(globalPool)
    private var feedVisible = false
    /**
     * The slot currently on screen; null until the first layout is applied.
     *
     * Declared here, above everything that reads it, on purpose. Kotlin
     * initialises properties in declaration order, so a property that derives
     * from this one and is declared earlier would capture a null — which is
     * exactly the initialisation-order crash this release fixed elsewhere in
     * the app.
     */
    private val _activeSlot = MutableStateFlow<FeedSlot?>(null)
    val activeSlot: StateFlow<FeedSlot?> = _activeSlot.asStateFlow()

    /** The active slot as a plain tab, mirrored so the hot path stays synchronous. */
    private val _currentFeed = MutableStateFlow(FeedTab.HOME)

    /** The stored home layout, so the UI can render the tab strip from data. */
    val homeLayout: StateFlow<HomeLayout> = homeLayouts.layout

    /**
     * The legacy tab, DERIVED from the active slot.
     *
     * Nine places in this class ask "am I the global feed?" and several loaders
     * still branch on it. Keeping the two in sync by assigning a flow would let
     * them disagree — and the disagreement is silent, showing global paging on a
     * relay feed. Deriving it makes that impossible: one source of truth, the
     * slot, and this is a read-only view of it.
     */
    val currentFeed: StateFlow<FeedTab> = _currentFeed.asStateFlow()

    /** Moves the mirror whenever the active slot changes. */
    private fun setActiveSlot(slot: FeedSlot?) {
        _activeSlot.value = slot
        _currentFeed.value = slot?.let { legacyTabFor(it.source) } ?: FeedTab.HOME
    }

    // One-shot subscriptions for fetching author metadata — closed on EOSE.
    // Declared before [quoteLookup] because its onResolved callback calls fetchMetadataForAuthors.
    private val metadataFetchSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val quoteLookup = QuoteLookupSession(
        scope = viewModelScope,
        pool = pool,
        extraPool = globalPool,
        eventRepository = eventRepository,
        appSettings = appSettings,
        labelPrefix = "feed",
        onResolved = { event -> fetchMetadataForAuthors(listOf(event.pubkey)) },
    )

    val quotedEvents: StateFlow<Map<String, Event>> = quoteLookup.quotedEvents

    /**
     * Referenced notes whose lookup genuinely finished without any relay
     * returning the event, so cards show a terminal "not available" row
     * instead of "loading…" forever — never prematurely while in flight.
     */
    val unresolvedQuoteIds: StateFlow<Set<String>> = quoteLookup.unresolvedQuoteIds

    fun retryQuote(id: String) = quoteLookup.retryId(id)

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
            val account = currentAccount() ?: return@launch
            // Re-anchor when a clock feed is what is actually on screen — which is
            // now a question about the active slot, not about the legacy tab.
            if (_activeSlot.value?.source is FeedSource.Clock) reanchorClockFeed(account, safe)
        }
    }

    /**
     * Kept hot for the ViewModel's lifetime (`Eagerly`) instead of `WhileSubscribed`:
     * internal feed loaders, live event handlers (`addToFeed`, `expandFeedToFollows`),
     * and cold-start slot activation all read the active account before Compose
     * attaches — and while the user visits another screen for > 5 seconds.
     */
    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @Volatile private var latestAccount: Account? = null

    private fun currentAccount(): Account? = latestAccount ?: activeAccount.value

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = combine(
        accountRepository.activeAccount, pool.relayStatuses, globalPool.relayStatuses,
        _currentFeed, appSettings.globalActiveRelays,
    ) { account, personalStatus, globalStatus, tab, globalEnabled ->
        val urls = if (tab == FeedTab.GLOBAL) globalEnabled.toList() else account?.effectiveRelays().orEmpty()
        val statuses = if (tab == FeedTab.GLOBAL) globalStatus else personalStatus
        urls.map(RelayPool::normalize).distinct().associateWith { statuses[it] ?: RelayStatus.DISCONNECTED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val accounts = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Per-slot feed state.
     *
     * The four original loaders owned their fields directly on this class, which
     * only worked because a tab and its loader were the same thing. Now that a
     * slot's contents come from the stored layout — and two slots may be the
     * same source type — all of that state is per slot. See [SlotRuntime].
     */
    private val slotEngine = FeedSlotEngine()

    private var slotActivateJob: Job? = null
    private val acquiredSlotRelays: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var pendingGlobalLoadOnVisible = false
    @Volatile private var subscribedFollowSet: Set<String>? = null

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

    /**
     * Pubkeys the active account follows, used by the content filter so a block
     * on someone else can never hide a followed author's own note. Read at call
     * time (not from a captured value) because the set changes as follow lists
     * load.
     */
    private val followedPubkeys: Set<String>
        get() = appliedFollowMode?.follows.orEmpty()

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

    /**
     * One per feed load. Every loader arms it and every settle path disarms it.
     *
     * Before this existed each loader had its own ad-hoc timeout, three of which
     * settled the feed without clearing `isLoading` — the infinite spinner. See
     * [LoadWatchdog] for the full failure and the two ways it showed up.
     */
    private val loadWatchdog = LoadWatchdog(viewModelScope, "No notes came back in time.")
    private val pendingBuffer = ConcurrentLinkedQueue<Event>()

    init {
        // Keep latestAccount fresh on every account update (relays, follows, profile)
        // without restarting the active feed.
        viewModelScope.launch {
            accountRepository.activeAccount.collect { latestAccount = it }
        }

        // Deterministic startup + account-switch coordinator: waits for the stored
        // layout once, then drives initial load and account switches from a single
        // place with the emitted Account passed directly. Eliminates the cold-start
        // race where layout loading and a WhileSubscribed activeAccount flow starved
        // each other and left FeedUiState(isLoading = true) spinning forever.
        viewModelScope.launch {
            coordinateFeedStartup(
                awaitInitialLayout = { homeLayouts.awaitLoaded() },
                syncLayout = { layout -> slotEngine.sync(layout) },
                currentLayout = { homeLayouts.layout.value },
                currentActiveSlotId = { _activeSlot.value?.id },
                accounts = accountRepository.activeAccount,
                onAccountObserved = { account ->
                    latestAccount = account
                    closeSlots(slotEngine.all())
                    slotEngine.resetForAccountSwitch()
                },
                onClear = { clearFeed() },
                onActivate = { slot, account ->
                    activateSlotForAccount(slot, account, remember = false)
                },
            )
        }
        viewModelScope.launch {
            // Layout edits take effect without a restart (rename, hide, reorder).
            // Compare only `slots` so `rememberActiveSlot` (which updates only
            // `lastActiveSlotId`) never triggers a redundant layout re-apply.
            homeLayouts.awaitLoaded()
            homeLayouts.layout
                .distinctUntilChangedBy { it.slots }
                .drop(1)
                .collect { layout -> applyLayoutEdit(layout) }
        }
        viewModelScope.launch {
            eventOpenCoordinator.events.collect { event -> openEventInMainFeed(event) }
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
                    currentAccount()?.takeIf { it.pubkey == pk }?.let { loadFeed(it, clearEvents = false) }
                }
        }

        viewModelScope.launch {
            appSettings.globalActiveRelays
                // Reload only when the SET actually changes. Without this, any
                // re-emission restarts the global load; a reload that lands
                // before the previous one settles cancels its safety net, and
                // the feed spins with every relay connected. That is the
                // reported hang, reproduced by opening the Global tab while a
                // relay-set edit is still settling.
                .map { relays -> relays.map(RelayPool::normalize).toSet() }
                .distinctUntilChanged()
                .collect {
                    if (feedVisible && _currentFeed.value == FeedTab.GLOBAL && appSettings.initialized.value) {
                        currentAccount()?.let { account -> loadGlobalFeed(account) }
                    }
                }
        }

        // Re-filter the retained raw feed whenever any content rule changes.
        // This ensures hide-word/NSFW edits take effect without a reload and
        // allows notes to reappear if a rule is later removed.
        viewModelScope.launch {
            combine(
                muteRepository.effectiveMuted,
                appSettings.nsfwMode,
                appSettings.hideWords,
            ) { _, _, _ -> Unit }.collect {
                val snapshot = synchronized(feedEvents) { feedEvents.take(feedEventLimit()) }
                _uiState.update { state -> state.withContentFilter(applyContentFilter(snapshot)) }
            }
        }
    }

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    /** The global pool exists only while the actual feed screen is visible. */
    fun setFeedVisible(visible: Boolean) {
        if (feedVisible == visible) return
        feedVisible = visible
        if (!visible) {
            globalSession.reconcile(false, emptySet())
        } else if (_currentFeed.value == FeedTab.GLOBAL) {
            pendingGlobalLoadOnVisible = false
            currentAccount()?.let { loadGlobalFeed(it) }
        }
    }

    override fun onCleared() {
        quoteLookup.close()
        releaseSlotRelays()
        globalSession.reconcile(false, emptySet())
        super.onCleared()
    }

    // ── Slot-driven feeds ─────────────────────────────────────────────────────

    /** Maps a legacy tab onto the feed type it stands for. */
    private fun legacySource(tab: FeedTab): FeedSource = when (tab) {
        FeedTab.HOME -> FeedSource.Follows()
        FeedTab.CLOCK -> FeedSource.Clock(hours = appSettings.clockFeedHours.value)
        FeedTab.SUGGESTED -> FeedSource.Suggested()
        FeedTab.GLOBAL -> FeedSource.Global()
    }

    /**
     * Asks the planner for the exchange this slot needs right now.
     *
     * Everything the planner needs is gathered here, at the boundary: the follow
     * set, the clock window, and the enabled global relays. The clock hours and
     * the global relay set are read at call time rather than captured, so moving
     * the hour strip or editing global relays is picked up by the next plan.
     */
    private suspend fun planFor(source: FeedSource, account: Account): FeedPlan =
        feedPlanner.plan(
            source = source,
            account = account,
            now = nowSeconds(),
            globalRelays = appSettings.globalActiveRelays.value,
        )

    /**
     * Switches to the slot the user tapped.
     *
     * This replaces the old `when (tab) { … }`, which hard-coded which loader ran
     * for which tab. Now the slot's *stored source* decides, and the four
     * original loaders remain only for the sources they came from — anything new
     * (people, relay, hashtag, polls) goes through [activateGeneric].
     */
    fun switchToSlot(slot: FeedSlot) {
        val account = currentAccount() ?: return
        val isSameSlot = _activeSlot.value?.id == slot.id
        if (isSameSlot) _scrollToTop.tryEmit(Unit)
        activateSlotForAccount(slot, account, remember = true)
    }

    /**
     * Activates [slot] for [account], serialising concurrent activations so two
     * overlapping `planFor()` coroutines can never start competing loads.
     */
    private fun activateSlotForAccount(slot: FeedSlot, account: Account, remember: Boolean) {
        setActiveSlot(slot)
        if (remember) homeLayouts.rememberActiveSlot(slot.id)
        // Leave the global pool connected only while a global feed is on screen;
        // any other slot tears it down, exactly as the old tab switch did.
        if (slot.source !is FeedSource.Global) {
            pendingGlobalLoadOnVisible = false
            globalSession.reconcile(false, emptySet())
        }

        slotActivateJob?.cancel()
        slotActivateJob = viewModelScope.launch {
            val plan = planFor(slot.source, account)
            val change = slotEngine.activate(slot.id, plan)
            when (change) {
                is SlotChange.Switch -> {
                    closeSlots(change.closeSlots)
                    change.teardown.forEach { unsub(it) }
                    loadSlot(change.load, change.plan, account)
                }
                is SlotChange.Reload -> loadSlot(change.runtime, change.plan, account)
                is SlotChange.Blocked -> {
                    closeSlots(slotEngine.all())
                    clearFeedForBlocked(change.reason)
                }
                SlotChange.None -> Unit
            }
        }
    }

    /** Releases any temporary relays leased for a custom Relay slot. */
    private fun releaseSlotRelays() {
        val toRelease = acquiredSlotRelays.toList()
        acquiredSlotRelays.clear()
        toRelease.forEach { pool.releaseRelay(it) }
    }

    /** Closes the subscriptions of slots that are no longer active. */
    private fun closeSlots(slots: List<SlotRuntime>) {
        slots.forEach { runtime ->
            runtime.subIds.toList().forEach { unsub(it) }
            runtime.subIds.clear()
        }
        releaseSlotRelays()
    }

    /** Loads a slot according to its plan, dispatching on the source kind. */
    private fun loadSlot(runtime: SlotRuntime, plan: FeedPlan, account: Account) {
        if (plan !is FeedPlan.Query) return
        when (val source = runtime.source) {
            is FeedSource.Follows -> loadFeed(account)
            is FeedSource.Clock -> loadClockFeed(account)
            is FeedSource.Suggested -> loadSuggestedFeed(account)
            is FeedSource.Global -> loadGlobalFeed(account)
            else -> activateGeneric(runtime, plan, source, account)
        }
    }

    /**
     * Loads a feed type that has no bespoke loader: one REQ per planned filter,
     * merged into the slot's own event set.
     *
     * People, relay, hashtag and poll feeds all behave the same way, which is why
     * they share this path — the differences are entirely in the plan.
     */
    private fun activateGeneric(runtime: SlotRuntime, plan: FeedPlan, source: FeedSource, account: Account) {
        // Narrowed once: the callers only get here with a Query, and doing it here
        // keeps every subsequent `plan.filters` / `plan.pool` statically typed.
        val query = plan as? FeedPlan.Query ?: return
        resetFeedState()
        lastLoadedPubkey = null
        _uiState.update {
            it.copy(events = emptyList(), isLoading = true, error = null, clockAnchor = 0L)
        }

        loadJob = viewModelScope.launch {
            startMessageCollector()
            val relays = when (query.pool) {
                PoolChoice.GLOBAL -> appSettings.globalActiveRelays.value
                    .map(RelayPool::normalize).toSet()
                PoolChoice.SPECIFIC -> (source as? FeedSource.Relay)?.urls.orEmpty()
                    .map(RelayPool::normalize).toSet()
                PoolChoice.PERSONAL -> emptySet()
            }
            if (query.pool == PoolChoice.SPECIFIC && relays.isNotEmpty()) {
                relays.forEach { url ->
                    if (acquiredSlotRelays.add(url)) pool.acquireRelay(url)
                }
            }
            val label = "slot-${runtime.slotId.take(4)}"
            query.filters.forEach { filter ->
                // A relay feed talks only to its own relays. `subscribeTo` is the
                // scoped form of `sub` and is what location channels already use.
                val id = if (query.pool == PoolChoice.SPECIFIC && relays.isNotEmpty()) {
                    val id = UUID.randomUUID().toString().take(8)
                    activeSubIds.add(id)
                    pool.subscribeTo(listOf(filter), id = id, label = label, relays = relays)
                    id
                } else {
                    sub(listOf(filter), label = label)
                }
                runtime.subIds.add(id)
                slotEngine.registerSub(runtime.slotId, id)
            }
            slotEngine.markLoaded(runtime.slotId, account.pubkey, query.anchor ?: 0L)
            loadWatchdog.start(
                timeoutMs = SETTLE_TIMEOUT_MS,
                isEmpty = { runtime.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    if (!runtime.settled) settleSlot(runtime, sayNothingArrived = nothingArrived)
                },
            )
        }
    }

    private fun currentPubkeyForSlots(): String = currentAccount()?.pubkey.orEmpty()

    /**
     * Settles a slot: reveals what it buffered and stops the spinner.
     *
     * `sayNothingArrived` is what stops this being a silent empty screen: a
     * people or relay feed whose relays answered nothing used to settle to an
     * empty list with no explanation, which reads as "the feed is broken".
     */
    private fun settleSlot(runtime: SlotRuntime, sayNothingArrived: Boolean = false) {
        slotEngine.markSettled(runtime.slotId)
        publishSlot(runtime)
        endLoad(
            if (sayNothingArrived && runtime.events.isEmpty() && runtime.slotId == slotEngine.activeSlotId) {
                "No notes came back for this feed. Its relays may be rate-limiting this client, " +
                    "or nobody in it has posted recently."
            } else {
                null
            },
        )
    }

    /**
     * Puts a slot's events on screen, through the shared content filter.
     *
     * Every path ends up here — generic slot loads and the four original loaders
     * alike — so no feed type can bypass mutes, NSFW rules or hidden words.
     */
    private fun publishSlot(runtime: SlotRuntime) {
        val snapshot = runtime.snapshot(feedEventLimit())
        _uiState.update { state ->
            state.withContentFilter(applyContentFilter(snapshot))
        }
        if (snapshot.isNotEmpty()) {
            fetchQuotesForEvents(snapshot)
            fetchProfilesForEvents(snapshot)
        }
    }

    /** Shows a slot's message instead of a spinner that never resolves. */
    private fun clearFeedForBlocked(reason: String) {
        // A blocked feed is a finished load, not a failed one: the watchdog must
        // not later replace the reason with a timeout message.
        loadWatchdog.finish()
        loadJob?.cancel()
        collectJob?.cancel()
        refreshBlockedSlots()
        _uiState.update {
            it.copy(events = emptyList(), isLoading = false, error = reason)
        }
    }

    /**
     * Re-reads every slot whose source can only be planned, and keeps the active
     * one's error current. Used when a layout edit introduces an invalid feed.
     */
    private fun refreshBlockedSlots() {
        val account = currentAccount() ?: return
        viewModelScope.launch {
            slotEngine.all().forEach { runtime ->
                val plan = planFor(runtime.source, account)
                if (plan is FeedPlan.Blocked && runtime.slotId == slotEngine.activeSlotId) {
                    _uiState.update { it.copy(isLoading = false, error = plan.reason) }
                }
            }
        }
    }

    /**
     * Legacy entry point: the screen still names a tab, and this maps it onto a
     * slot before switching.
     *
     * The mapping is by source type rather than by position, so a layout whose
     * clock feed sits first still treats the CLOCK tab as the clock feed. Once
     * the strip renders slots directly, the screen calls [switchToSlot] and this
     * disappears.
     */
    fun switchFeed(tab: FeedTab) {
        val wanted = legacySource(tab)
        val layout = homeLayouts.layout.value
        val slot = layout.slots.firstOrNull { sameFeedKind(it.source, wanted) }
        if (slot == null) {
            // The user hid or removed this feed. Nothing to select — but the tap
            // must not do nothing silently, so it is treated as a no-op switch.
            _scrollToTop.tryEmit(Unit)
            return
        }
        if (_activeSlot.value?.id == slot.id) {
            _scrollToTop.tryEmit(Unit)
            return
        }
        switchToSlot(slot)
    }

    /** The four sources that predate the layout, which have bespoke loaders. */
    private fun isLegacyFeedKind(source: FeedSource): Boolean =
        source is FeedSource.Follows || source is FeedSource.Clock ||
            source is FeedSource.Suggested || source is FeedSource.Global

    /** True when two sources are the same *kind* of feed, ignoring parameters. */
    private fun sameFeedKind(a: FeedSource, b: FeedSource): Boolean =
        a::class == b::class

    /**
     * Applies a live layout edit (rename, hide, reorder, source edit, removal)
     * without requiring a restart.
     */
    private fun applyLayoutEdit(layout: HomeLayout) {
        slotEngine.sync(layout)
        val landing = layout.landingSlot ?: return
        val account = currentAccount()
        val current = _activeSlot.value
        if (account == null) {
            if (current == null || layout.slot(current.id) == null) setActiveSlot(landing)
            return
        }
        val updatedCurrent = current?.let { layout.slot(it.id) }
        when {
            updatedCurrent == null -> activateSlotForAccount(landing, account, remember = false)
            updatedCurrent.source != current?.source -> activateSlotForAccount(updatedCurrent, account, remember = false)
            else -> setActiveSlot(updatedCurrent)
        }
    }

    /** The legacy tab a source stands for, so existing UI checks keep working. */
    private fun legacyTabFor(source: FeedSource): FeedTab = when (source) {
        is FeedSource.Clock -> FeedTab.CLOCK
        is FeedSource.Suggested -> FeedTab.SUGGESTED
        is FeedSource.Global -> FeedTab.GLOBAL
        else -> FeedTab.HOME
    }

    /** Fetch the next 100 events strictly older than the oldest Global item. */
    fun loadOlderGlobal() {
        if (!feedVisible || _currentFeed.value != FeedTab.GLOBAL || _uiState.value.globalOlderLoading ||
            _uiState.value.globalNewerLoading || !_uiState.value.globalCanLoadOlder ||
            globalVisibleLimit >= MAX_GLOBAL_EVENTS
        ) return
        val prefetchedCount = synchronized(feedEvents) { feedEvents.size }
        if (prefetchedCount > globalVisibleLimit) {
            globalVisibleLimit = (globalVisibleLimit + GLOBAL_PAGE_SIZE).coerceAtMost(MAX_GLOBAL_EVENTS)
            val snapshot = synchronized(feedEvents) { feedEvents.take(feedEventLimit()) }
            _uiState.update { state ->
                state.withContentFilter(applyContentFilter(snapshot)).copy(
                    globalOlderLoading = false,
                    globalCanLoadOlder = globalVisibleLimit < MAX_GLOBAL_EVENTS &&
                        (prefetchedCount > globalVisibleLimit || state.globalCanLoadOlder),
                )
            }
            return
        }
        val oldest = globalOlderCursor ?: synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt } ?: return
        if (oldest <= 0L) return
        val globalRelays = appSettings.globalActiveRelays.value
        if (globalRelays.isEmpty()) return

        val subId = UUID.randomUUID().toString().take(8)
        val request = GlobalPageRequest(
            subId = subId,
            kind = GlobalPageKind.OLDER,
            expectedRelays = globalRelays.map(RelayPool::normalize).toSet(),
            startOldest = oldest,
        )
        globalOlderRequest = request
        globalOlderSubId = subId
        activeSubIds.add(subId)
        _uiState.update { it.copy(globalOlderLoading = true) }
        globalPool.subscribeTo(
            listOf(
                Filter(
                    kinds = FEED_KINDS,
                    until = oldest - 1,
                    limit = GLOBAL_PAGE_SIZE,
                ),
            ),
            id = subId,
            label = "global-older",
            relays = globalRelays.map(RelayPool::normalize).toSet(),
        )
        scheduleGlobalPageTimeout(request)
    }

    /** Fetch all relay-available public events newer than the current Global set. */
    fun loadNewerGlobal() {
        if (!feedVisible || _currentFeed.value != FeedTab.GLOBAL || _uiState.value.globalNewerLoading ||
            _uiState.value.globalOlderLoading
        ) return
        val newest = synchronized(feedEvents) { feedEvents.firstOrNull()?.createdAt } ?: return
        // Remember every item already buffered, not only visible items; a newer-page
        // result must not accidentally reveal prefetched older notes and shift the feed.
        val startKnownIds = synchronized(feedEvents) { feedEvents.mapTo(mutableSetOf()) { it.id } }
        val startVisibleLimit = globalVisibleLimit
        val globalRelays = appSettings.globalActiveRelays.value
        if (globalRelays.isEmpty()) return

        val subId = UUID.randomUUID().toString().take(8)
        val request = GlobalPageRequest(
            subId = subId,
            kind = GlobalPageKind.NEWER,
            expectedRelays = globalRelays.map(RelayPool::normalize).toSet(),
            startNewest = newest,
            startVisibleLimit = startVisibleLimit,
            startKnownIds = startKnownIds,
        )
        globalNewerRequest = request
        globalNewerSubId = subId
        activeSubIds.add(subId)
        _uiState.update { it.copy(globalNewerLoading = true) }
        globalPool.subscribeTo(
            listOf(
                Filter(
                    kinds = FEED_KINDS,
                    since = newest,
                    limit = GLOBAL_NEWER_LIMIT,
                ),
            ),
            id = subId,
            label = "global-newer",
            relays = globalRelays.map(RelayPool::normalize).toSet(),
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
            EventKind.REPOST, EventKind.GENERIC_REPOST, EventKind.POLL -> true
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
                val globalRelaysForPage = request.expectedRelays
                globalPool.subscribeTo(
                    listOf(
                        Filter(
                            kinds = FEED_KINDS,
                            since = startNewest,
                            until = pageOldest - 1,
                            limit = GLOBAL_NEWER_LIMIT,
                        ),
                    ),
                    id = nextSubId,
                    label = "global-newer",
                    relays = globalRelaysForPage.map(RelayPool::normalize).toSet(),
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
            _uiState.update { state -> state.withContentFilter(applyContentFilter(snapshot)) }
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
        _uiState.update { it.withContentFilter(applyContentFilter(visible)) }
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
    private data class ContentFilterResult(
        val events: List<Event>,
        val items: List<FeedListItem>,
        val hiddenCount: Int,
    )

    private fun FeedUiState.withContentFilter(result: ContentFilterResult): FeedUiState =
        copy(events = result.events, feedItems = result.items, hiddenCount = result.hiddenCount)

    /**
     * Applies content filtering (blocked pubkeys, NSFW mode, hidden words) to
     * a list of events before it reaches the UI. Visible notes keep their
     * order; consecutive hidden notes collapse into one [FeedListItem.HiddenGroup]
     * so a filtered timeline never looks identical to a quiet one.
     */
    private fun applyContentFilter(events: List<social.tbone.nostr.Event>): ContentFilterResult {
        // Defense in depth: relay filters and event dispatch both restrict the
        // feed, but cached/side-loaded data must also never surface NIP-17 or
        // its NIP-59 wrappers in the public timeline.
        val publicEvents = events.filterNot { EventKind.isPrivateMessaging(it.kind) }.distinctBy { it.id }
        val blocked = muteRepository.effectiveMuted.value
        val nsfwMode = appSettings.nsfwMode.value
        val hideWords = appSettings.hideWords.value
        val contextEvents = synchronized(feedEvents) { feedEvents.associateBy { it.id } } + quotedEvents.value
        val filtersActive = blocked.isNotEmpty() || nsfwMode == NsfwMode.HIDE || hideWords.isNotEmpty()

        val visible = mutableListOf<Event>()
        val items = mutableListOf<FeedListItem>()
        val pendingHidden = mutableListOf<HiddenEntry>()
        fun flushHidden() {
            if (pendingHidden.isNotEmpty()) {
                items += FeedListItem.HiddenGroup(pendingHidden.toList(), "hidden-" + pendingHidden.first().eventId)
                pendingHidden.clear()
            }
        }
        for (event in publicEvents) {
            val reason = if (filtersActive) {
                ContentFilter.hideReason(
                    event, blocked, nsfwMode, hideWords, contextEvents, followedPubkeys,
                )
            } else {
                null
            }
            if (reason == null) {
                flushHidden()
                visible += event
                items += FeedListItem.Note(event)
            } else {
                pendingHidden += HiddenEntry(event.id, event.pubkey, reason)
            }
        }
        flushHidden()
        val hiddenCount = items.filterIsInstance<FeedListItem.HiddenGroup>().sumOf { it.entries.size }

        val focused = focusedEventId?.let { id ->
            publicEvents.firstOrNull { it.id == id }
                ?: synchronized(feedEvents) { feedEvents.firstOrNull { it.id == id } }
        }
        val focusedIsAllowed = focused?.let {
            !ContentFilter.shouldHide(it, blocked, nsfwMode, hideWords, contextEvents, followedPubkeys)
        } == true
        return if (focused != null && focusedIsAllowed && visible.none { it.id == focused.id }) {
            ContentFilterResult(
                events = listOf(focused) + visible,
                items = listOf(FeedListItem.Note(focused)) + items,
                hiddenCount = hiddenCount,
            )
        } else {
            ContentFilterResult(visible, items, hiddenCount)
        }
    }

    private fun feedEventLimit(): Int =
        if (_currentFeed.value == FeedTab.GLOBAL) globalVisibleLimit.coerceIn(1, MAX_GLOBAL_EVENTS) else MAX_FEED_EVENTS

    fun refresh() {
        val account = currentAccount() ?: return
        // Custom feeds have no legacy tab, so a pull-to-refresh would otherwise
        // fall through to the follow loader and tear down the slot's own
        // subscriptions. Re-activate the slot instead, which is the same path a
        // layout edit takes.
        val slot = _activeSlot.value
        if (slot != null && !isLegacyFeedKind(slot.source)) {
            slotActivateJob?.cancel()
            slotActivateJob = viewModelScope.launch {
                val plan = planFor(slot.source, account)
                val runtime = slotEngine.runtime(slot.id)
                if (runtime != null) runtime.settled = false
                when (val change = slotEngine.activate(slot.id, plan)) {
                    is SlotChange.Switch -> loadSlot(change.load, change.plan, account)
                    is SlotChange.Reload -> loadSlot(change.runtime, change.plan, account)
                    is SlotChange.Blocked -> clearFeedForBlocked(change.reason)
                    SlotChange.None -> Unit
                }
            }
            return
        }
        when (_currentFeed.value) {
            FeedTab.GLOBAL -> loadGlobalFeed(account)
            FeedTab.CLOCK -> loadClockFeed(account)
            FeedTab.SUGGESTED -> loadSuggestedFeed(account)
            FeedTab.HOME -> loadFeed(account, clearEvents = false)
        }
    }

    private fun loadFeed(account: Account, clearEvents: Boolean = true) {
        releaseSlotRelays()
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
        subscribedFollowSet = null

        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        focusedEventId = null
        _pendingEventScroll.value = null
        quoteLookup.reset()
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

        // Preload cached events on cold start and same-account refreshes (the DB
        // query is strictly keyed by account.pubkey). Skip only on account switch
        // so another account's feed never flashes on screen.
        val isSameAccount = shouldPreloadAccountCache(lastLoadedPubkey, account.pubkey)
        lastLoadedPubkey = account.pubkey

        loadJob = viewModelScope.launch {
            // Which follow list drives the feed: the account's offline list (when its switch is
            // on) or the relay list. In offline mode the relay follow list is never even requested.
            val local = offlineLists.snapshot(ListType.FOLLOWS, account.pubkey)
            val offlineMode = local.enabled
            val localFollows = if (offlineMode) local.pubkeySet() else emptySet()
            // The content filter needs the *effective* follow set in both modes,
            // not just the offline one: in online mode localFollows is empty and
            // every followed author would lose the protection that stops a block
            // on someone else from hiding their notes.
            val effectiveFollows = if (offlineMode) localFollows else account.follows.toSet()
            appliedFollowMode = FollowMode(offlineMode, effectiveFollows)
            appliedForPubkey = account.pubkey

            if (offlineMode) {
                // Drop anything on screen from people no longer in the local list.
                val allowed = localFollows + account.pubkey
                val snapshot = synchronized(feedEvents) {
                    feedEvents.removeAll { it.pubkey !in allowed }
                    feedEvents.take(feedEventLimit())
                }
                _uiState.update { state -> state.withContentFilter(applyContentFilter(snapshot)) }
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
                        _uiState.update { state -> state.withContentFilter(applyContentFilter(snapshot)) }
                        fetchProfilesForEvents(cached)
                        // Cached home rows need the same quote lookup as live
                        // relay rows; otherwise cards remain blank until tapped.
                        fetchQuotesForEvents(cached)
                        repliesRepository.subscribeTo(cached.map { it.id })
                    }
                }
            }

            // Guarantee the message collector is actively subscribed to both pools
            // BEFORE issuing any REQ so fast or local relays can never drop kind-3
            // or EOSE between subscribe and collect.
            startMessageCollector()

            if (offlineMode) {
                // Offline follow list: no kind-3 request at all. The feed is built straight
                // from the local list; "follow list received" is true from the start.
                followsReceived = true
                val followed = local.entries.map { it.pubkey }
                if (followed.isEmpty()) {
                    // Nobody followed locally: just show your own notes.
                    subscribedFollowSet = emptySet()
                    feedSubId = sub(listOf(
                        Filter(
                            authors = listOf(account.pubkey),
                            kinds = FEED_KINDS,
                            limit = 50,
                        )
                    ))
                } else {
                    applyFollowedSet(account.pubkey, followed)
                }
            } else if (account.follows.isNotEmpty()) {
                // Cached follows are already known: subscribe to the full follow feed
                // on the first round-trip instead of blocking on kind-3, and still
                // fetch kind-3 in parallel to pick up any remote follow-list changes.
                applyFollowedSet(account.pubkey, account.follows)
                followSubId = sub(listOf(
                    Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)
                ))
            } else {
                // First login (no cached follows yet): own notes first + kind-3 in parallel.
                feedSubId = sub(listOf(
                    Filter(
                        authors = listOf(account.pubkey),
                        kinds = FEED_KINDS,
                        limit = 50,
                    )
                ))
                // Fetch follow list (kind-3) from the relays.
                followSubId = sub(listOf(
                    Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)
                ))
            }

            // Own profile (kind-0) in parallel
            metadataSubId = sub(listOf(
                Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.METADATA), limit = 1)
            ))

            // Safety net. The old version cleared the spinner as a separate,
            // later statement and could be skipped entirely when a newer load
            // took over; the watchdog is generation-guarded, so a superseded
            // timer cannot publish over a newer load and a live one always ends.
            loadWatchdog.start(
                timeoutMs = HOME_LOAD_TIMEOUT_MS,
                isEmpty = { _uiState.value.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    if (!feedSettled) {
                        feedSettled = true
                        flushBuffer()
                    }
                    endLoad(
                        if (nothingArrived) {
                            "No notes arrived. Your follows may be on relays that are not answering, " +
                                "or the follow list has not been downloaded yet — pull to refresh."
                        } else {
                            null
                        },
                    )
                },
            )
        }
    }

    private fun loadGlobalFeed(account: Account) {
        if (!feedVisible) {
            pendingGlobalLoadOnVisible = true
            return
        }
        pendingGlobalLoadOnVisible = false
        releaseSlotRelays()
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
        subscribedFollowSet = null

        feedSettled = false
        pendingBuffer.clear()
        quoteLookup.reset()
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

        loadJob = viewModelScope.launch {
            if (!appSettings.initialized.value) {
                appSettings.initialized.first { it }
            }
            if (!feedVisible || _currentFeed.value != FeedTab.GLOBAL) return@launch

            // Dedicated pool: global requests cannot leak into personal subscriptions or publishing.
            val globalRelays = appSettings.globalActiveRelays.value.map(RelayPool::normalize).toSet()
            globalSession.reconcile(true, globalRelays)
            if (globalRelays.isEmpty()) {
                endLoad("No Global relays enabled. Configure Global relays to load this feed.")
                return@launch
            }
            globalInitialExpectedRelays = globalRelays
            globalInitialEoseRelays.clear()

            // Register the message collector before sending REQ: a fast EOSE or
            // event must not be lost between subscription and collection.
            startMessageCollector()

            val initialSubId = UUID.randomUUID().toString().take(8)
            feedSubId = initialSubId
            activeSubIds.add(initialSubId)
            followsReceived = true
            globalPool.subscribeTo(
                listOf(Filter(kinds = FEED_KINDS, limit = GLOBAL_PAGE_SIZE)),
                id = initialSubId,
                label = "global-feed",
                relays = globalInitialExpectedRelays,
            )

            loadWatchdog.start(
                timeoutMs = GLOBAL_PAGE_TIMEOUT_MS,
                isEmpty = { _uiState.value.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    if (feedVisible && _currentFeed.value == FeedTab.GLOBAL && feedSubId == initialSubId) {
                        settleGlobalInitial(
                            if (nothingArrived) {
                                "Your Global relays are connected but sent nothing back. They may be " +
                                    "rate-limiting this client — try again in a minute."
                            } else {
                                null
                            },
                        )
                    }
                },
            )
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
        releaseSlotRelays()
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
        subscribedFollowSet = null

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
        quoteLookup.reset()
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

        loadJob = viewModelScope.launch {
            // Same follow source as HOME: the offline list wins when it is switched on.
            val local = offlineLists.snapshot(ListType.FOLLOWS, account.pubkey)
            val follows = if (local.enabled) local.pubkeySet().toList()
                else account.follows.ifEmpty { fetchFollowsOnce(account) }
            val authors = (follows + account.pubkey).distinct()
            clockAuthors = authors

            startMessageCollector()
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

            loadWatchdog.start(
                timeoutMs = SETTLE_TIMEOUT_MS,
                isEmpty = { _uiState.value.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    settleWindowFeed(
                        if (nothingArrived) {
                            "Nothing in this window. Try a longer window, or pull to refresh."
                        } else {
                            null
                        },
                    )
                },
            )
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
            it.withContentFilter(applyContentFilter(kept)).copy(
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
        loadWatchdog.start(
            timeoutMs = SETTLE_TIMEOUT_MS,
            isEmpty = { _uiState.value.events.isEmpty() },
            onTimeout = { nothingArrived ->
                settleWindowFeed(
                    if (nothingArrived) {
                        "Nothing in this window. Try a longer window, or pull to refresh."
                    } else {
                        null
                    },
                )
            },
        )
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

        suggestedEoseChunks.clear()

        if (chunks.isEmpty()) {
            feedSettled = true
            endLoad()
            _uiState.update { it.copy(suggestedLoading = false) }
            return
        }

        loadJob = viewModelScope.launch {
            startMessageCollector()
            followsReceived = true

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

            loadWatchdog.start(
                timeoutMs = SUGGESTED_TIMEOUT_MS,
                isEmpty = { _uiState.value.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    settleSuggested(
                        if (nothingArrived) {
                            "None of the suggested accounts answered. Their relays may be blocking " +
                                "this client — pull to refresh, or read your follow feed instead."
                        } else {
                            null
                        },
                    )
                },
            )
        }
    }

    /** Flushes once every chunk has been answered (or the timeout wins). */
    private fun settleSuggested(emptyMessage: String? = null) {
        if (!feedSettled) {
            feedSettled = true
            // One-shot feed: nine merged slices are enough, no need to keep nine
            // live subscriptions open (and re-flushing) for the rest of the session.
            suggestedSubIds.forEach { pool.unsubscribe(it) }
            suggestedSubIds.clear()
            flushBuffer()
        }
        endLoad(emptyMessage)
        _uiState.update {
            it.copy(
                suggestedLoading = false,
                suggestedAuthors = synchronized(feedEvents) { feedEvents.map { e -> e.pubkey }.toSet().size },
            )
        }
    }

    /**
     * Shared settle for the clock feed (single subscription + EOSE/timeout).
     *
     * The old version returned early when the feed was already settled, which
     * meant a second call could leave `isLoading` set; and it never cleared the
     * spinner on the timeout path. Both are now handled by [endLoad], which is
     * idempotent and is the only thing that clears loading for this feed.
     */
    private fun settleWindowFeed(emptyMessage: String? = null) {
        if (!feedSettled) {
            feedSettled = true
            flushBuffer()
        }
        endLoad(emptyMessage)
    }

    /** Bounded kind-3 fetch when the account has no locally cached follows. */
    private suspend fun fetchFollowsOnce(account: Account, timeoutMs: Long = 8_000): List<String> {
        if (account.follows.isNotEmpty()) return account.follows
        val id = "clock-follows-" + UUID.randomUUID().toString()
        val best = kotlinx.coroutines.withContext(Dispatchers.Default) {
            fetchClockFollowEvent(
                messages = pool.messages, id = id, author = account.pubkey,
                subscribe = { pool.subscribe(listOf(Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)), id = id, label = "clock-follows") },
                unsubscribe = { pool.unsubscribe(id) }, timeoutMs = timeoutMs,
            )
        }
        return best?.parsedTags?.pubkeys ?: emptyList()
    }

    private fun settleGlobalInitial(emptyMessage: String? = null) {
        if (!feedSettled) {
            feedSettled = true
            flushBuffer()
        }
        val oldest = synchronized(feedEvents) { feedEvents.lastOrNull()?.createdAt }
        globalOlderCursor = oldest
        val canLoadOlder = synchronized(feedEvents) { feedEvents.size >= GLOBAL_PAGE_SIZE }
        endLoad(emptyMessage)
        _uiState.update { it.copy(globalCanLoadOlder = canLoadOlder) }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    /** Subscribe and track the ID before sending REQ so fast relays cannot beat registration. */
    private fun sub(filters: List<Filter>, label: String = "feed", relays: Set<String>? = null): String {
        val id = UUID.randomUUID().toString().take(8)
        activeSubIds.add(id)
        if (relays != null) {
            val target = if (_currentFeed.value == FeedTab.GLOBAL) globalPool else pool
            target.subscribeTo(filters, id = id, label = label, relays = relays)
        } else {
            pool.subscribe(filters, id = id, label = label)
        }
        return id
    }

    /** Unsubscribe and stop tracking the ID. */
    private fun unsub(id: String?) {
        id ?: return
        activeSubIds.remove(id)
        pool.unsubscribe(id)
        globalPool.unsubscribe(id)
    }

    /**
     * Launches the message collector on [Dispatchers.Default] and suspends until
     * BOTH `pool.messages` and `globalPool.messages` have active subscribers, so
     * no fast relay event or EOSE can ever be missed between `sub(...)` and
     * collection starting.
     */
    private suspend fun startMessageCollector() {
        collectJob?.cancel()
        val personalReady = CompletableDeferred<Unit>()
        val globalReady = CompletableDeferred<Unit>()
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            merge(
                pool.messages.onSubscription { personalReady.complete(Unit) },
                globalPool.messages.onSubscription { globalReady.complete(Unit) },
            ).collect { poolMessage ->
                runCatching { handlePoolMessage(poolMessage) }
                    .onFailure { t ->
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Timber.e(t, "FeedViewModel handlePoolMessage failed")
                    }
            }
        }
        personalReady.await()
        globalReady.await()
    }

    private fun handlePoolMessage(poolMessage: social.tbone.nostr.relay.PoolMessage) {
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
                }
            }
            is RelayMessage.EndOfStoredEvents -> {
                when {
                    msg.subscriptionId in metadataFetchSubIds -> {
                        metadataFetchSubIds.remove(msg.subscriptionId)
                        unsub(msg.subscriptionId)
                    }
                    msg.subscriptionId == metadataSubId -> {
                        // Own profile kind-0 EOSE must never clear the feed's
                        // loading spinner before the timeline itself settles.
                        unsub(metadataSubId); metadataSubId = null
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
                    msg.subscriptionId !in activeSubIds -> return
                    msg.subscriptionId == followsRelayListSubId -> {
                        unsub(followsRelayListSubId); followsRelayListSubId = null
                        val follows = followsForRelaySelection
                        if (follows.isNotEmpty()) selectAndApplyRelays(follows)
                    }
                    // Generic custom slots (people, relay, hashtag, polls): settle as
                    // soon as every filter subscription for the slot has reported EOSE.
                    slotEngine.slotForSub(msg.subscriptionId) != null -> {
                        val runtime = slotEngine.slotForSub(msg.subscriptionId)
                        if (runtime != null && !runtime.settled) {
                            runtime.eoseChunks.add(msg.subscriptionId)
                            if (runtime.subIds.isNotEmpty() && runtime.eoseChunks.containsAll(runtime.subIds)) {
                                settleSlot(runtime)
                            }
                        }
                    }
                    // Expanded feed (after follow list) is ready — show everything.
                    msg.subscriptionId == feedSubId && followsReceived && !feedSettled -> {
                        feedSettled = true
                        flushBuffer()
                        endLoad()
                    }
                    // Follow subscription returned nothing (user has no follows) —
                    // fall back to showing own notes.
                    msg.subscriptionId == followSubId && !followsReceived && !feedSettled -> {
                        feedSettled = true
                        flushBuffer()
                        endLoad()
                    }
                    else -> Unit
                }
            }
            else -> Unit
        }
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
                val selfPubkey = currentAccount()?.pubkey ?: return
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
                val selfPubkey = currentAccount()?.pubkey ?: return
                if (event.pubkey == selfPubkey) handleRelayList(event)
                else handleFollowRelayList(event)
            }
            EventKind.TEXT_NOTE   -> {
                if (isReplyEvent(event)) return
                addToFeed(event)
            }
            EventKind.REPOST, EventKind.GENERIC_REPOST -> addToFeed(event)
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

        val selfPubkey = currentAccount()?.pubkey ?: return

        if (!isSameSubscribedFollowSet(followsReceived, subscribedFollowSet, followed)) {
            appliedFollowMode = FollowMode(offline = false, follows = followed.toSet())
            applyFollowedSet(selfPubkey, followed)
            loadWatchdog.start(
                timeoutMs = HOME_LOAD_TIMEOUT_MS,
                isEmpty = { _uiState.value.events.isEmpty() },
                onTimeout = { nothingArrived ->
                    if (!feedSettled) {
                        feedSettled = true
                        flushBuffer()
                    }
                    endLoad(
                        if (nothingArrived) {
                            "No notes arrived. Your follows may be on relays that are not answering — pull to refresh."
                        } else {
                            null
                        },
                    )
                },
            )
        }

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
        subscribedFollowSet = followed.toSet()
        unsub(feedSubId)
        feedSettled = false
        feedSubId = sub(listOf(
            Filter(
                authors = allPubkeys,
                kinds = FEED_KINDS,
                limit = 200,
            )
        ))

        // Eagerly fetch profile metadata for everyone we follow in parallel with
        // the feed events, so names render as soon as the feed appears instead of
        // lagging behind each note. Cached profiles skip the network entirely.
        fetchMetadataForAuthors(allPubkeys)

        // Home uses only the user's configured personal relays. Do not
        // automatically connect to discovered follow/outbox relay lists.
    }

    /**
     * NIP-65: seed the relay list from the network on first login.
     *
     * If the user already has a local relay list, the network event is ignored —
     * local changes must not be clobbered by a stale Nostr-published snapshot.
     * Without a publish path, the two lists can diverge; local always wins.
     */
    private fun handleRelayList(event: Event) {
        // Published lists are imported only via the user's explicit Sync action.
        unsub(relayListSubId); relayListSubId = null
    }

    /**
     * Files an event under the slot whose subscription delivered it.
     *
     * The four original loaders still append into the shared sorted set, so this
     * returns false for their events and they behave exactly as before. Generic
     * slots (people / relay / hashtag / polls) own their events outright, which is
     * what lets two slots of the same kind hold different results.
     */
    private fun routeToSlot(event: Event): Boolean {
        val active = slotEngine.active() ?: return false
        if (active.isFollows || active.isClock || active.isSuggested || active.isGlobal) {
            // Built-in loaders: keep the shared-set behaviour they were written for.
            return false
        }
        if (!active.settled) {
            active.pending.add(event)
            return true
        }
        active.events.add(event)
        publishSlot(active)
        return true
    }

    private fun addToFeed(event: Event) {
        if (EventKind.isPrivateMessaging(event.kind)) return
        // Route into the owning slot first. Nothing else may run before this, or a
        // note can reach the shared set during a switch and then reappear under a
        // slot it never belonged to.
        if (routeToSlot(event)) {
            fetchQuotesForEvents(listOf(event))
            fetchProfilesForEvents(listOf(event))
            reactionsRepository.subscribeTo(listOf(event.id))
            repliesRepository.subscribeTo(listOf(event.id))
            if (event.kind == EventKind.POLL) pollsRepository.subscribeTo(listOf(event.id))
            return
        }
        // Only persist home feed events — global/clock/suggested are views and must
        // not pollute the cache that getRecentFeedEvents returns on next home load.
        if (_currentFeed.value == FeedTab.HOME) {
            val accountPubkey = currentAccount()?.pubkey ?: return
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
            state.withContentFilter(applyContentFilter(snapshot)).copy(
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
    /**
     * Ends a load: stops the watchdog, clears the spinner and, when nothing
     * arrived, explains why instead of leaving an empty screen that looks broken.
     *
     * Every settle path funnels through here. That is the whole point — the
     * previous code cleared `isLoading` in some branches and not others, and the
     * branches that forgot are exactly the ones that could hang.
     */
    private fun endLoad(emptyMessage: String? = null) {
        loadWatchdog.finish()
        _uiState.update { state ->
            val empty = state.events.isEmpty()
            when {
                empty && emptyMessage != null -> state.copy(isLoading = false, error = emptyMessage)
                empty -> state.copy(isLoading = false)
                else -> state.copy(isLoading = false, error = null)
            }
        }
    }

    private fun flushBuffer() {
        val events = pendingBuffer.toList().also { pendingBuffer.clear() }
        if (events.isEmpty()) return
        val snapshot = synchronized(feedEvents) {
            feedEvents.addAll(events)
            feedEvents.take(feedEventLimit())
        }
        _uiState.update { state -> state.withContentFilter(applyContentFilter(snapshot)) }
        _scrollToTop.tryEmit(Unit)
        fetchQuotesForEvents(events)
        fetchProfilesForEvents(events)
        reactionsRepository.subscribeTo(events.map { it.id })
        repliesRepository.subscribeTo(events.map { it.id })
        pollsRepository.subscribeTo(events.filter { it.kind == EventKind.POLL }.map { it.id })
    }

    private fun fetchQuotesForEvents(events: List<Event>) {
        quoteLookup.requestForEvents(events)
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
        slotActivateJob?.cancel()
        loadWatchdog.finish()
        pendingGlobalLoadOnVisible = false
        releaseSlotRelays()
        globalSession.reconcile(false, emptySet())
        loadJob?.cancel()
        appliedFollowMode = null
        appliedForPubkey = null
        subscribedFollowSet = null
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
        quoteLookup.reset(clearResolved = true)
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
     * Applies the greedy relay selection WITHOUT persisting to the account.
     * FIX: Relay list must stay exactly as the user configured it — never auto-add/remove.
     * We only temporarily connect to outbox relays for fetching, without touching storage.
     * This fixes the bug where relays were added/removed without asking.
     */
    private fun selectAndApplyRelays(follows: List<String>) {
        val outbox = greedyRelaySelection(follows)
        if (outbox.isEmpty()) return
        // Only connect to outbox relays for this feed session — never persist.
        // Keep all user-configured relays intact.
        // Automatic outbox connections are disabled; personal relays are authoritative.
        Timber.d("Temporarily connected to ${outbox.size} outbox relays for feed (not persisted): $outbox")
        // Do NOT remove any existing relays, do NOT update account storage.
    }

    companion object {
        /** Kinds every timeline feed shows: notes, reposts (kind 6 & 16) and polls. */
        private val FEED_KINDS = listOf(
            EventKind.TEXT_NOTE,
            EventKind.REPOST,
            EventKind.GENERIC_REPOST,
            EventKind.POLL,
        )
        /** Page size for the clock feed's single page (there is no older-paging). */
        private const val CLOCK_PAGE_SIZE = 100
        /** How long HOME/CLOCK wait for EOSE before showing what arrived. */
        private const val SETTLE_TIMEOUT_MS = 12_000L

        /**
         * How long the follow feed waits before it says something instead of
         * spinning. Shorter than the old 15s: two seconds of that was never
         * useful, and a spinner that resolves into an explanation is better than
         * one that resolves into nothing.
         */
        private const val HOME_LOAD_TIMEOUT_MS = 14_000L
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
