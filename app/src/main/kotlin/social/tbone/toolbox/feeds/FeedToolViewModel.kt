package social.tbone.toolbox.feeds

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.db.EventRepository
import social.tbone.di.GlobalFeedPool
import social.tbone.feed.TimelineSupport
import social.tbone.home.FeedPlan
import social.tbone.home.FeedPlanner
import social.tbone.home.PoolChoice
import social.tbone.ui.feed.LoadWatchdog
import social.tbone.ui.feed.QuoteLookupSession
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.lists.MuteListRepository
import social.tbone.settings.ContentFilter
import java.util.UUID
import javax.inject.Inject

/**
 * The feed viewer tool: open any feed type as a tab, read it, close it.
 *
 * ### Why this is separate from [social.tbone.ui.feed.FeedViewModel]
 * The home feed's view model is a 2,000-line machine built around one feed being
 * on screen, with four loaders that each own fields. Twelve concerns are welded
 * into it (quote resolution, relay reconciliation, the offline follow list,
 * brightness, the clock anchor). Threading a second, independent feed through it
 * would have meant a tab strip that changes what "the" feed means, which is
 * exactly the coupling phase 4 spent its time removing.
 *
 * So this view model is deliberately narrow: it plans a source with the *same*
 * [FeedPlanner] the home feeds use, subscribes on the personal or global pool,
 * and publishes what arrived. It does not resolve quotes, does not follow the
 * account's relay list beyond what the pool already does, and does not decide
 * which feeds exist — the tool's launcher does that, from a fixed menu of types
 * plus the user's saved feeds.
 *
 * ### What it shares, on purpose
 * Content filtering, profile metadata, and the muting rules all come from the
 * same singletons the home feed uses, so a note hidden there is hidden here. The
 * one thing it will not do is store events in the local database: the viewer is a
 * window, and the home feed's cache belongs to the home feed.
 */
@HiltViewModel
class FeedToolViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val appSettings: AppSettings,
    private val planner: FeedPlanner,
    private val muteRepository: MuteListRepository,
    private val pool: RelayPool,
    @GlobalFeedPool private val globalPool: RelayPool,
) : ViewModel() {

    /**
     * The signed-in account, held as state.
     *
     * A plain `StateFlow` rather than the raw `Flow`: [load] needs the account
     * synchronously when a tab is opened, and suspending the tap handler to read
     * a DataStore-backed flow would make opening a tab feel laggy for no reason.
     */
    private val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tabs currently open, left to right. */
    private val _tabs = MutableStateFlow<List<FeedTabState>>(emptyList())
    val tabs: StateFlow<List<FeedTabState>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    /** What the active tab is showing. */
    private val _uiState = MutableStateFlow(FeedToolUiState())
    val uiState: StateFlow<FeedToolUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    private val _scrollToTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToTop: SharedFlow<Unit> = _scrollToTop.asSharedFlow()

    /** Every subscription this view model opened, so sign-out can close them all. */
    private val openSubIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val quoteLookup = QuoteLookupSession(
        scope = viewModelScope,
        pool = pool,
        extraPool = globalPool,
        eventRepository = eventRepository,
        appSettings = appSettings,
        labelPrefix = "viewer",
        onResolved = { ev -> fetchMetadataForPubkeys(listOf(ev.pubkey)) },
    )

    val quotedEvents: StateFlow<Map<String, Event>> = quoteLookup.quotedEvents
    val unresolvedQuoteIds: StateFlow<Set<String>> = quoteLookup.unresolvedQuoteIds

    fun retryQuote(id: String) = quoteLookup.retryId(id)

    /**
     * Guards the active tab's load, so a relay that accepts the REQ and then
     * says nothing cannot leave the viewer spinning. Same failure, same fix as
     * the home feed's: the timeout is the only thing allowed to end a load.
     */
    private val loadWatchdog = LoadWatchdog(viewModelScope, "No notes came back in time.")

    /** The collector for the active tab. Only one runs at a time. */
    private var collectJob: Job? = null

    /** The tab whose events the pool currently delivers. */
    private var subscribedTabId: String? = null

    /** Global relays the current tab asked the global pool to connect to. */
    private var globalScopeRelays: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            activeAccount
                .distinctUntilChanged { old, new -> old?.pubkey == new?.pubkey }
                .collect { account ->
                    // A tab's contents came from the previous account's follows and
                    // relays. Keeping them would show one identity's people under
                    // another's name, so every tab is emptied and the active one is
                    // re-read (or emptied outright if there is no account).
                    closeAllSubscriptions()
                    quoteLookup.reset(clearResolved = true)
                    _tabs.value.forEach {
                        it.clear()
                        it.settled = false
                        it.blockedReason = null
                    }
                    _uiState.value = FeedToolUiState()
                    if (account == null) {
                        _tabs.value = emptyList()
                        _activeTabId.value = null
                    } else {
                        _activeTabId.value?.let { id -> reload(id) }
                    }
                }
        }
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    /**
     * Opens [source] as a new tab and switches to it.
     *
     * An identical source already open is focused instead of duplicated: two tabs
     * of the same relay feed would double every REQ for no visible difference.
     */
    fun open(source: social.tbone.home.FeedSource) {
        val existing = _tabs.value.firstOrNull { it.source == source }
        if (existing != null) {
            select(existing.tabId)
            _scrollToTop.tryEmit(Unit)
            return
        }
        val tab = FeedTabState(tabId = UUID.randomUUID().toString(), source = source)
        _tabs.update { it + tab }
        select(tab.tabId)
    }

    fun select(tabId: String) {
        if (_tabs.value.none { it.tabId == tabId }) return
        if (_activeTabId.value == tabId) return
        _activeTabId.value = tabId
        loadActiveTab()
    }

    fun close(tabId: String) {
        val tabs = _tabs.value
        val index = tabs.indexOfFirst { it.tabId == tabId }
        if (index < 0) return
        val closing = tabs[index]
        closeTabSubscriptions(closing)
        val remaining = tabs.toMutableList().also { it.removeAt(index) }
        _tabs.value = remaining
        if (_activeTabId.value == tabId) {
            // Move to the tab that took its place (the one on the right), or the
            // last one left. Never to nothing if any tab is still open.
            val next = remaining.getOrNull(index) ?: remaining.lastOrNull()
            _activeTabId.value = null
            if (next != null) select(next.tabId) else _uiState.value = FeedToolUiState()
        }
    }

    fun closeAll() {
        loadWatchdog.finish()
        _tabs.value.forEach { closeTabSubscriptions(it) }
        _tabs.value = emptyList()
        _activeTabId.value = null
        _uiState.value = FeedToolUiState()
    }

    /** Pull-to-refresh for the active tab. */
    fun refresh() {
        _activeTabId.value?.let { id ->
            _tabs.value.firstOrNull { it.tabId == id }?.let { tab ->
                tab.clear()
                tab.settled = false
            }
            closeTabSubscriptionsFor(id)
            load(id)
        }
    }

    /** Re-reads a tab without clearing what is on screen (clock window change). */
    private fun reload(tabId: String) {
        closeTabSubscriptionsFor(tabId)
        load(tabId)
    }

    // ── Loading ──────────────────────────────────────────────────────────────

    private fun loadActiveTab() {
        val id = _activeTabId.value ?: return
        val tab = _tabs.value.firstOrNull { it.tabId == id } ?: return
        publish(tab)
        if (tab.blockedReason != null) return
        // Already has this tab's notes on screen and no reload pending: nothing
        // to do, which is what makes switching back to a tab instant.
        if (tab.settled && subscribedTabId == id) return
        load(id)
    }

    private fun load(tabId: String) {
        val account = activeAccount.value ?: return
        val tab = _tabs.value.firstOrNull { it.tabId == tabId } ?: return
        val active = _activeTabId.value == tabId
        if (!active) return

        _uiState.update {
            it.copy(isLoading = tab.events.isEmpty(), error = null, blockedReason = null)
        }

        loadWatchdog.start(
            timeoutMs = SETTLE_TIMEOUT_MS + 1_500L,
            isEmpty = { tab.events.isEmpty() },
            onTimeout = { nothingArrived ->
                tab.settled = true
                tab.flush()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        events = applyContentFilter(tab.snapshot(MAX_EVENTS)),
                        error = if (nothingArrived) {
                            "This relay accepted the request but sent nothing back. " +
                                "It may be rate-limiting this client — try again, or pick a different relay."
                        } else {
                            null
                        },
                    )
                }
            },
        )

        viewModelScope.launch(Dispatchers.Default) {
            val plan = planner.plan(
                source = tab.source,
                account = account,
                globalRelays = appSettings.globalActiveRelays.value.toSet(),
            )
            when (plan) {
                is FeedPlan.Blocked -> {
                    loadWatchdog.finish()
                    tab.blockedReason = plan.reason
                    tab.settled = true
                    _uiState.update {
                        it.copy(isLoading = false, blockedReason = plan.reason, events = emptyList())
                    }
                }
                is FeedPlan.Query -> subscribe(tab, plan, account)
            }
        }
    }

    private fun subscribe(tab: FeedTabState, plan: FeedPlan.Query, account: Account) {
        tab.lastFilters = plan.filters
        tab.canPageOlder = plan.canPageOlder
        tab.blockedReason = null

        // Start collecting before the REQ goes out, so the first notes a relay
        // sends cannot land before anybody is listening.
        startCollector()

        val relays: Set<String> = when (plan.pool) {
            PoolChoice.PERSONAL -> emptySet()
            PoolChoice.SPECIFIC -> (tab.source as? social.tbone.home.FeedSource.Relay)
                ?.urls.orEmpty()
                .map(RelayPool::normalize)
                .toSet()
            PoolChoice.GLOBAL -> appSettings.globalActiveRelays.value
                .map(RelayPool::normalize)
                .toSet()
        }
        tab.relayScope = relays.takeIf { plan.pool == PoolChoice.SPECIFIC }
        tab.usesGlobalPool = plan.pool == PoolChoice.GLOBAL

        // A relay feed may name relays the personal pool has never connected to.
        // They are acquired rather than added, so they are released again when
        // the tab closes instead of becoming permanent personal relays.
        if (plan.pool == PoolChoice.SPECIFIC) {
            relays.forEach(pool::acquireRelay)
        }
        if (plan.pool == PoolChoice.GLOBAL) {
            // The global pool owns its own relay set; it must be told which ones
            // to dial, exactly as the home feed's global session does.
            if (globalScopeRelays != relays) {
                (globalPool.relayUrls() - relays).forEach(globalPool::removeRelay)
                (relays - globalPool.relayUrls()).forEach(globalPool::addRelay)
                globalScopeRelays = relays
            }
        }

        val targetPool = if (plan.pool == PoolChoice.GLOBAL) globalPool else pool
        plan.filters.forEach { filter ->
            val subId = "${tab.label()}-${UUID.randomUUID().toString().take(6)}"
            targetPool.subscribeTo(
                filters = listOf(filter),
                id = subId,
                label = tab.label(),
                relays = if (plan.pool == PoolChoice.SPECIFIC) relays else null,
            )
            openSubIds.add(subId)
            tab.subIds.add(subId)
        }
        subscribedTabId = tab.tabId

        // Settle on a timer rather than on EOSE: a relay feed scoped to three
        // relays may get one EOSE and two silences, and waiting for all three
        // would leave the reader on a spinner. The timer is the same one the home
        // feed uses for its generic slots.
        viewModelScope.launch {
            delay(SETTLE_TIMEOUT_MS)
            if (!tab.settled) settle(tab)
        }

        fetchMissingMetadata(tab)
    }

    private fun settle(tab: FeedTabState) {
        tab.settled = true
        tab.flush()
        if (_activeTabId.value == tab.tabId) {
            loadWatchdog.finish()
            publish(tab)
        }
    }

    /**
     * Asks for profiles for everybody who has posted, so names and avatars fill
     * in. Batched, because a relay feed can surface a hundred strangers at once
     * and one 100-author REQ is more likely to be dropped than four of 25.
     */
    private fun fetchMissingMetadata(tab: FeedTabState) {
        val targetPool = if (tab.usesGlobalPool) globalPool else pool
        val known = profileRepository.profiles.value.keys
        val missing = tab.events.asSequence().map { it.pubkey }.filter { it !in known }.distinct().toList()
        if (missing.isEmpty()) return
        TimelineSupport.metadataFilters(missing).forEach { filter ->
            val subId = "${tab.label()}-meta-${UUID.randomUUID().toString().take(6)}"
            targetPool.subscribeTo(filters = listOf(filter), id = subId, label = "viewer-meta", relays = tab.relayScope)
            openSubIds.add(subId)
            tab.subIds.add(subId)
            viewModelScope.launch {
                delay(METADATA_GRACE_MS)
                if (openSubIds.remove(subId)) targetPool.unsubscribe(subId)
                tab.subIds.remove(subId)
            }
        }
    }

    private fun startCollector() {
        if (collectJob?.isActive == true) return
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            kotlinx.coroutines.flow.merge(pool.messages, globalPool.messages).collect { poolMessage ->
                when (val message = poolMessage.message) {
                    is RelayMessage.EventMessage -> {
                        if (message.subscriptionId !in openSubIds) return@collect
                        val tab = _tabs.value.firstOrNull { message.subscriptionId in it.subIds }
                            ?: return@collect
                        val event = message.event
                        if (!event.verify()) return@collect
                        if (!TimelineSupport.admitsContent(event)) return@collect
                        if (event.kind == social.tbone.nostr.EventKind.METADATA) {
                            profileRepository.processEvent(event)
                            return@collect
                        }
                        tab.buffer(event)
                        if (tab.settled && _activeTabId.value == tab.tabId) publish(tab)
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        val tab = _tabs.value.firstOrNull { message.subscriptionId in it.subIds }
                            ?: return@collect
                        tab.eoseSubs.add(message.subscriptionId)
                        // Every REQ answered: show it all now rather than at the
                        // timeout, which is the difference between "instant" and
                        // "two seconds of spinner" on a fast relay.
                        if (tab.eoseSubs.containsAll(tab.subIds)) settle(tab)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** Asks for the next page of older notes on the active tab. */
    fun loadOlder() {
        val id = _activeTabId.value ?: return
        val tab = _tabs.value.firstOrNull { it.tabId == id } ?: return
        if (!tab.canPageOlder || tab.loadingOlder) return
        val cursor = tab.oldestAt
        if (cursor <= 0L) return
        val filters = tab.lastFilters.map { it.copy(until = cursor - 1, limit = PAGE_SIZE) }
        if (filters.isEmpty()) return
        tab.loadingOlder = true
        _uiState.update { it.copy(loadingOlder = true) }

        // Paging stays on the pool the tab loaded from: the global feed's older
        // page must come from the global set, not the account's own relays.
        val targetPool = if (tab.usesGlobalPool) globalPool else pool
        filters.forEach { filter ->
            val subId = "${tab.label()}-old-${UUID.randomUUID().toString().take(6)}"
            targetPool.subscribeTo(filters = listOf(filter), id = subId, label = tab.label(), relays = tab.relayScope)
            openSubIds.add(subId)
            tab.subIds.add(subId)
            // A paging subscription is one-shot: it exists to answer one question.
            viewModelScope.launch {
                delay(PAGE_GRACE_MS)
                if (openSubIds.remove(subId)) targetPool.unsubscribe(subId)
                tab.subIds.remove(subId)
                tab.loadingOlder = false
                if (_activeTabId.value == tab.tabId) {
                    _uiState.update { it.copy(loadingOlder = false) }
                    publish(tab)
                }
            }
        }
    }

    // ── Publishing ───────────────────────────────────────────────────────────

    private fun fetchMetadataForPubkeys(pubkeys: List<String>) {
        val known = profileRepository.profiles.value.keys
        val missing = pubkeys.filter { it !in known }.distinct()
        if (missing.isEmpty()) return
        TimelineSupport.metadataFilters(missing).forEach { filter ->
            val subId = "viewer-qmeta-${UUID.randomUUID().toString().take(6)}"
            pool.subscribe(filters = listOf(filter), id = subId, label = "viewer-meta")
            openSubIds.add(subId)
            viewModelScope.launch {
                delay(METADATA_GRACE_MS)
                if (openSubIds.remove(subId)) pool.unsubscribe(subId)
            }
        }
    }

    private fun publish(tab: FeedTabState) {
        loadWatchdog.finish()
        val raw = tab.snapshot(MAX_EVENTS)
        quoteLookup.requestForEvents(raw)
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    events = applyContentFilter(raw),
                    isLoading = false,
                    error = null,
                    canPageOlder = tab.canPageOlder,
                    loadingOlder = tab.loadingOlder,
                    blockedReason = tab.blockedReason,
                )
            }
        }
    }

    /**
     * The same content rules the home feed applies — the same singleton, the same
     * settings — so a muted person, a hidden word or an NSFW mode hides a note
     * here too.
     *
     * Differences from the home feed are deliberate and small:
     *  - hidden notes are dropped rather than collapsed into an inline "N hidden"
     *    row, because this is a viewer for a feed you chose to look at, not a
     *    chronological position you are scrolling through;
     *  - the following set is empty, so mute inheritance through a boost is judged
     *    on the muted account alone. Resolving that properly needs the follow
     *    graph and the referenced-event map, which the home feed already keeps and
     *    this view model does not; showing a boost of a muted account is the
     *    failure mode, and it is the conservative one.
     */
    private fun applyContentFilter(events: List<Event>): List<Event> {
        val blocked = muteRepository.effectiveMuted.value
        val nsfwMode = appSettings.nsfwMode.value
        val hideWords = appSettings.hideWords.value
        if (blocked.isEmpty() && nsfwMode != social.tbone.settings.NsfwMode.HIDE && hideWords.isEmpty()) {
            return events
        }
        return events.filterNot { event ->
            ContentFilter.shouldHide(event, blocked, nsfwMode, hideWords)
        }
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    private fun closeTabSubscriptions(tab: FeedTabState) {
        tab.subIds.toList().forEach { subId ->
            if (openSubIds.remove(subId)) {
                pool.unsubscribe(subId)
                globalPool.unsubscribe(subId)
            }
        }
        tab.subIds.clear()
        tab.eoseSubs.clear()
        // Relays acquired for this tab go back, so a relay feed does not leave the
        // personal pool dialling a relay the user never added.
        tab.relayScope?.forEach(pool::releaseRelay)
        tab.relayScope = null
        if (subscribedTabId == tab.tabId) subscribedTabId = null
    }

    private fun closeTabSubscriptionsFor(tabId: String) {
        _tabs.value.firstOrNull { it.tabId == tabId }?.let { closeTabSubscriptions(it) }
    }

    private fun closeAllSubscriptions() {
        _tabs.value.forEach { tab -> closeTabSubscriptions(tab) }
        openSubIds.toList().forEach { subId ->
            pool.unsubscribe(subId)
            globalPool.unsubscribe(subId)
        }
        openSubIds.clear()
        collectJob?.cancel()
        collectJob = null
        subscribedTabId = null
    }

    override fun onCleared() {
        super.onCleared()
        quoteLookup.close()
        closeAllSubscriptions()
        // The global pool is shared; leaving it dialling relays the viewer added
        // would be a leak the home feed never asked for.
        globalScopeRelays.forEach(globalPool::removeRelay)
        globalScopeRelays = emptySet()
    }

    private companion object {
        /** Matches the home feed's page size so paging feels identical. */
        const val PAGE_SIZE = 100
        const val MAX_EVENTS = 500
        const val SETTLE_TIMEOUT_MS = 2_500L
        const val METADATA_GRACE_MS = 4_000L
        const val PAGE_GRACE_MS = 4_000L
    }
}

/** What the feed viewer is showing right now. */
data class FeedToolUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = false,
    val loadingOlder: Boolean = false,
    val canPageOlder: Boolean = false,
    val error: String? = null,
    /** Set when the planner refused the source; a message, never a spinner. */
    val blockedReason: String? = null,
)
