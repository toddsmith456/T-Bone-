package social.tbone.ui.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.db.EventRepository
import social.tbone.lists.ListEntry
import social.tbone.lists.ListType
import social.tbone.lists.MuteListRepository
import social.tbone.lists.MuteOutcome
import social.tbone.lists.OfflineListRepository
import social.tbone.lists.RelayListFetcher
import social.tbone.account.FollowEntry
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.reactions.ReactionsRepository
import social.tbone.reactions.PollsRepository
import social.tbone.reactions.RepliesRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.isReply
import social.tbone.nostr.quotedEventId
import social.tbone.ui.feed.MediaItem
import social.tbone.ui.feed.QuoteLookupSession
import social.tbone.ui.feed.extractInlineQuoteIds
import social.tbone.ui.feed.parseNoteContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Profile feed tabs, in display order.
 *
 * NOTES is the default and holds only top-level notes: conversations belong in
 * REPLIES, and a feed whose main tab is mostly other people's half of a
 * conversation is hard to read. Pinned notes are no longer a tab of their own —
 * they are lifted to the top of NOTES and ALL instead, where they are actually
 * seen. The enum is ordered because the tab strip renders `entries`.
 */
enum class ProfileTab(val label: String) {
    NOTES("NOTES"),
    ALL("ALL"),
    REPLIES("REPLIES"),
    MEDIA("MEDIA"),
}

/** One image or video attachment in the profile's media gallery. */
data class ProfileMedia(val url: String, val noteId: String, val isVideo: Boolean = false)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pool: RelayPool,
    private val profileRepository: ProfileRepository,
    private val accountRepository: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val reactionsRepository: ReactionsRepository,
    private val repliesRepository: RepliesRepository,
    private val pollsRepository: PollsRepository,
    private val eventRepository: EventRepository,
    private val appSettings: social.tbone.settings.AppSettings,
    private val offlineLists: OfflineListRepository,
    private val muteRepository: MuteListRepository,
    private val listFetcher: RelayListFetcher,
    private val followMutations: social.tbone.lists.FollowMutationCoordinator,
    private val pins: social.tbone.lists.PinListRepository,
) : ViewModel() {

    val pubkey: String = checkNotNull(savedStateHandle["pubkey"])

    val profile: StateFlow<ProfileContent?> = profileRepository.profiles
        .map { it[pubkey] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), profileRepository.getProfile(pubkey))

    /** All known profiles — used to render quoted notes and mention names. */
    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _tab = MutableStateFlow(ProfileTab.NOTES)
    /** Which feed the profile is showing. */
    val tab: StateFlow<ProfileTab> = _tab.asStateFlow()

    fun selectTab(newTab: ProfileTab) {
        if (_tab.value != newTab) _tab.value = newTab
    }

    /**
     * Top-level notes only — what the NOTES tab shows. Replies (NIP-10 replies
     * and NIP-22 comments) are excluded; they live in [replyNotes].
     */
    private val _notes = MutableStateFlow<List<Event>>(emptyList())
    val notes: StateFlow<List<Event>> = _notes.asStateFlow()

    /** Everything: top-level notes plus replies and reposts. Drives the ALL tab. */
    private val _allNotes = MutableStateFlow<List<Event>>(emptyList())
    val allNotes: StateFlow<List<Event>> = _allNotes.asStateFlow()
    /** Raw relay events retained so changing filters can update the visible tabs. */
    private val profileEvents = ConcurrentHashMap<String, Event>()

    /** Only the profile's replies — every note carrying a NIP-10 reply marker. */
    private val _replyNotes = MutableStateFlow<List<Event>>(emptyList())
    val replyNotes: StateFlow<List<Event>> = _replyNotes.asStateFlow()

    /** Every image/video attachment in the profile's own notes (media tab). */
    private val _mediaItems = MutableStateFlow<List<ProfileMedia>>(emptyList())
    val mediaItems: StateFlow<List<ProfileMedia>> = _mediaItems.asStateFlow()

    private val allSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val quoteLookup = QuoteLookupSession(
        scope = viewModelScope,
        pool = pool,
        eventRepository = eventRepository,
        appSettings = appSettings,
        labelPrefix = "profile",
        onResolved = { event ->
            rebuildProfileFeed()
            fetchMetadataFor(listOf(event.pubkey))
        },
    )

    /** Quoted/reposted notes, resolved so the cards can render them. */
    val quotedEvents: StateFlow<Map<String, Event>> = quoteLookup.quotedEvents

    /**
     * Accounts the active user follows, for the same block-inheritance rule the
     * feed uses. `activeAccount` is a cold Flow, so this is cached rather than
     * read synchronously.
     */
    private val followedPubkeys: kotlinx.coroutines.flow.StateFlow<Set<String>> =
        accountRepository.activeAccount
            .map { it?.follows?.toSet().orEmpty() }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    /**
     * Pinned notes in pin-list order, lifted to the top of the feed the way
     * every other client does it. [pinnedFirst] is what the NOTES and ALL tabs
     * render; [pinnedNotes] is kept for callers that want the pins alone.
     */
    fun pinnedFirst(events: List<Event>, pinned: List<Event>): List<Event> {
        if (pinned.isEmpty()) return events
        val pinnedIds = pinned.map { it.id }.toSet()
        val byId = events.associateBy { it.id }
        // A pin whose note is not in this feed view (a reply pinned while the
        // NOTES tab hides replies) is still shown: pinning it is an explicit
        // choice and it must not silently disappear.
        val head = pinned.map { byId[it.id] ?: it }
        return head + events.filterNot { it.id in pinnedIds }
    }

    // Resolved against the FULL feed, not the top-level notes: a pinned reply is
    // a legitimate pin and would otherwise never resolve to an event.
    val pinnedNotes: StateFlow<List<Event>> = combine(pins.lists, quotedEvents, _allNotes) { lists, quotes, notes ->
        val byId = notes.associateBy { it.id } + quotes
        pins.ids(lists[pubkey]).mapNotNull { byId[it] }.filterNot { event ->
            social.tbone.settings.ContentFilter.shouldHide(
                event,
                muteRepository.effectiveMuted.value,
                appSettings.nsfwMode.value,
                appSettings.hideWords.value,
                byId,
                followedPubkeys.value,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Referenced notes whose lookup genuinely finished without any relay
     * returning the event, so cards show a terminal "not available" row.
     */
    val unresolvedQuoteIds: StateFlow<Set<String>> = quoteLookup.unresolvedQuoteIds

    fun retryQuote(id: String) = quoteLookup.retryId(id)

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isActiveUserProfile: StateFlow<Boolean> = accountRepository.activeAccount
        .map { it?.pubkey == pubkey }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Offline follow list ON  → true iff the profile is in the LOCAL list (relay list ignored).
     * Offline follow list OFF → the account's relay-backed follow list, as before.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val isFollowing: StateFlow<Boolean> = accountRepository.activeAccount
        .flatMapLatest { account ->
            if (account == null) flowOf(false)
            else offlineLists.state(ListType.FOLLOWS, account.pubkey).map { local ->
                if (local.enabled) local.contains(pubkey) else account.isFollowing(pubkey)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while the follow button edits the local list instead of the relay list. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val followIsLocal: StateFlow<Boolean> = accountRepository.activeAccount
        .flatMapLatest { account ->
            if (account == null) flowOf(false)
            else offlineLists.state(ListType.FOLLOWS, account.pubkey).map { it.enabled }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** True while the block button edits the local list instead of the relay mute list. */
    val blockIsLocal: StateFlow<Boolean> = muteRepository.offlineEnabled

    /** BLOCK state: local list when offline mode is on, otherwise the NIP-51 relay mute list. */
    val isBlocked: StateFlow<Boolean> = muteRepository.effectiveMuted
        .map { set -> pubkey in set }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), pubkey in muteRepository.effectiveMuted.value)

    private val _isBlockLoading = MutableStateFlow(false)
    val isBlockLoading: StateFlow<Boolean> = _isBlockLoading.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-shot user-facing messages (snackbar). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun toggleBlock() {
        if (_isBlockLoading.value) return
        viewModelScope.launch {
            _isBlockLoading.update { true }
            val wantMuted = pubkey !in muteRepository.effectiveMuted.value
            val outcome = runCatching { muteRepository.setMuted(pubkey, wantMuted) }
                .getOrElse { Timber.w(it, "toggleBlock failed"); MuteOutcome.SIGN_FAILED }
            when (outcome) {
                MuteOutcome.NO_SIGNER -> _messages.tryEmit("No signer available — mute list not changed")
                MuteOutcome.RELAYS_UNREACHABLE ->
                    _messages.tryEmit("Couldn't reach your relays to read your mute list — nothing changed")
                MuteOutcome.SIGN_FAILED -> _messages.tryEmit("Signing failed — mute list not changed")
                MuteOutcome.NO_ACCOUNT -> Unit
                MuteOutcome.REJECTED -> _messages.tryEmit("Can't mute that account")
                MuteOutcome.SAVED_LOCAL, MuteOutcome.PUBLISHED, MuteOutcome.UNCHANGED -> Unit
            }
            _isBlockLoading.update { false }
        }
    }

    private val _isFollowLoading = MutableStateFlow(false)
    val isFollowLoading: StateFlow<Boolean> = _isFollowLoading.asStateFlow()

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

    private var notesSubId: String? = null

    init {
        // Kind 6 (reposts) included: the "all" feed is supposed to show what the
        // profile amplified, not just what they wrote.
        notesSubId = pool.subscribe(listOf(
            Filter(
                authors = listOf(pubkey),
                kinds = listOf(
                    EventKind.TEXT_NOTE,
                    EventKind.REPOST,
                    EventKind.GENERIC_REPOST,
                    EventKind.POLL,
                    EventKind.COMMENT,
                ),
                limit = PROFILE_FEED_LIMIT,
            ),
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.METADATA), limit = 1),
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.PIN_LIST), limit = 1),
        ), label = "profile")
        notesSubId?.let { allSubIds.add(it) }

        viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                when (message) {
                    is RelayMessage.EventMessage -> {
                        val event = message.event
                        if (!event.verify()) return@collect
                        when (event.kind) {
                            EventKind.METADATA -> profileRepository.processEvent(event)
                            EventKind.PIN_LIST -> if (event.pubkey == pubkey) {
                                pins.consume(event)
                                fetchPinnedNotes(pins.ids(pins.lists.value[pubkey]))
                            }
                            EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.GENERIC_REPOST,
                            EventKind.POLL, EventKind.COMMENT ->
                                if (event.pubkey == pubkey) {
                                    // Store the raw event and derive visible tabs from
                                    // the current filters, so changing settings updates
                                    // this screen without reloading the profile.
                                    ingest(event)
                                    // Persist so a tapped note opens a thread instantly.
                                    viewModelScope.launch { eventRepository.save(event, "") }
                                    reactionsRepository.subscribeTo(listOf(event.id))
                                    repliesRepository.subscribeTo(listOf(event.id))
                                    if (event.kind == EventKind.POLL) {
                                        pollsRepository.subscribeTo(listOf(event.id))
                                    }
                                    fetchQuotesFor(listOf(event))
                                    fetchMetadataFor(listOf(event.pubkey))
                                }
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        if (message.subscriptionId == notesSubId) {
                            _isLoading.update { false }
                        }
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            combine(
                muteRepository.effectiveMuted,
                appSettings.nsfwMode,
                appSettings.hideWords,
            ) { _, _, _ -> Unit }.collect { rebuildProfileFeed() }
        }
        viewModelScope.launch {
            delay(10_000)
            _isLoading.update { false }
        }
    }

    /**
     * A reply, by either convention: NIP-22 comments (kind 1111) are replies by
     * definition, and plain notes count when they carry a NIP-10 reply marker.
     */
    private fun isReply(event: Event): Boolean =
        event.kind == EventKind.COMMENT ||
            (event.kind == EventKind.TEXT_NOTE && event.parsedTags.isReply)

    /**
     * Adds an event to the profile feed and refreshes the derived views:
     * top-level notes, everything, replies-only, and the media gallery.
     */
    private fun ingest(event: Event) {
        profileEvents[event.id] = event
        if (profileEvents.size > MAX_FEED_EVENTS) {
            profileEvents.values.minByOrNull { it.createdAt }?.let { profileEvents.remove(it.id) }
        }
        rebuildProfileFeed()
    }

    /** Re-derives all visible profile tabs from raw relay events and current rules. */
    private fun rebuildProfileFeed() {
        val raw = profileEvents.values
            .sortedByDescending { it.createdAt }
            .take(MAX_FEED_EVENTS)
        val contextEvents = raw.associateBy { it.id } + quotedEvents.value
        val visible = raw.filterNot { event ->
            social.tbone.settings.ContentFilter.shouldHide(
                event,
                muteRepository.effectiveMuted.value,
                appSettings.nsfwMode.value,
                appSettings.hideWords.value,
                contextEvents,
                followedPubkeys.value,
            )
        }
        val replies = visible.filter { event -> isReply(event) }
        _allNotes.value = visible
        _notes.value = visible.filterNot { event -> isReply(event) }
        _replyNotes.value = replies
        _mediaItems.value = visible
            .filter { it.kind == EventKind.TEXT_NOTE || it.kind == EventKind.COMMENT }
            .flatMap { note ->
                parseNoteContent(note.content).mediaItems
                    .filter { it is MediaItem.Image || it is MediaItem.Video }
                    .map { media ->
                        ProfileMedia(
                            url = when (media) {
                                is MediaItem.Image -> media.url
                                is MediaItem.Video -> media.url
                                is MediaItem.Audio -> ""
                            },
                            noteId = note.id,
                            isVideo = media is MediaItem.Video,
                        )
                    }
                    .filter { it.url.isNotBlank() }
            }
            .distinctBy { it.url }
    }

    private fun fetchQuotesFor(events: List<Event>) {
        quoteLookup.requestForEvents(events)
    }

    private fun fetchPinnedNotes(ids: List<String>) {
        quoteLookup.requestIds(ids)
    }

    private fun fetchMetadataFor(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { profileRepository.profiles.value[it] == null }
        if (unknown.isEmpty()) return
        val subId = pool.subscribe(
            listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))),
            label = "profile-metadata",
        )
        allSubIds += subId
    }

    fun react(event: Event) = reactionsRepository.react(event)

    fun voteOnPoll(poll: Event, optionIds: List<String>) =
        pollsRepository.vote(poll, optionIds)

    /** Reposts (kind-6) a note from the profile feed. */
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

    fun follow() = toggleFollow(add = true)
    fun unfollow() = toggleFollow(add = false)

    private fun toggleFollow(add: Boolean) {
        viewModelScope.launch {
          followMutations.mutex.withLock {
            val mutationJob = checkNotNull(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job])
            if (!followMutations.begin(mutationJob)) {
                _messages.tryEmit("Follow list unavailable during a wipe")
                return@launch
            }
            try {
            _isFollowLoading.update { true }
            try {
                val account = accountRepository.activeAccount.first() ?: return@launch

                // ── Offline follow list ON: edit the LOCAL list only. ────────────
                // No signer, no relay read, no publish — the relay follow list is never touched.
                if (offlineLists.snapshot(ListType.FOLLOWS, account.pubkey).enabled) {
                    if (add) offlineLists.add(ListType.FOLLOWS, account.pubkey, ListEntry(pubkey))
                    else offlineLists.remove(ListType.FOLLOWS, account.pubkey, pubkey)
                    return@launch
                }

                // ── Offline OFF: publish to the relays (original behaviour). ─────
                val signer = signerFactory.forActiveAccount() ?: return@launch

                // Base the new list on the NEWEST list on the relays rather than the cached
                // mirror, which can be stale (e.g. after a long stretch in offline mode).
                if (signer.pubkey != account.pubkey || account.effectiveRelays().isEmpty()) {
                    _messages.tryEmit("No signer or configured relays — follow list not changed")
                    return@launch
                }
                val fetched = listFetcher.fetchLatest(account.pubkey, EventKind.FOLLOW_LIST, account.effectiveRelays())
                if (!fetched.allRelaysAnswered) {
                    _messages.tryEmit("Couldn't read the follow list from every relay — nothing changed")
                    return@launch
                }
                val latest = followMutations.newest(account.pubkey, fetched.event)
                if ((latest?.createdAt ?: 0L) > System.currentTimeMillis() / 1000 + 59) {
                    _messages.tryEmit("Existing follow list has a future timestamp — nothing changed")
                    return@launch
                }
                if (!social.tbone.lists.sameListWriteContext(account, accountRepository.activeAccount.first()) ||
                    offlineLists.snapshot(ListType.FOLLOWS, account.pubkey).enabled) {
                    _messages.tryEmit("Account or list mode changed — try again")
                    return@launch
                }

                val existingEntries: List<FollowEntry> = latest?.let { ev ->
                    OfflineListRepository.entriesFromEvent(ev).map { FollowEntry(it.pubkey, it.relay, it.petname) }
                } ?: account.followEntries.ifEmpty {
                    // Fall back to the legacy pubkey-only list for accounts not yet updated.
                    account.follows.map { FollowEntry(pubkey = it) }
                }
                val newEntries = if (add)
                    (existingEntries + FollowEntry(pubkey = pubkey)).distinctBy { it.pubkey }
                else
                    existingEntries.filter { it.pubkey != pubkey }

                val tags = social.tbone.lists.followTagsForToggle(latest, existingEntries, pubkey, add)
                val unsigned = UnsignedEvent(
                    pubkey = signer.pubkey,
                    // Replaceable event: must be strictly newer than the one it replaces.
                    createdAt = maxOf(System.currentTimeMillis() / 1000, (latest?.createdAt ?: 0L) + 1),
                    kind = EventKind.FOLLOW_LIST,
                    content = latest?.content ?: "",
                    tags = tags,
                )

                signer.signEvent(unsigned)
                    .onSuccess { event ->
                        if (!social.tbone.lists.sameListWriteContext(account, accountRepository.activeAccount.first()) ||
                            offlineLists.snapshot(ListType.FOLLOWS, account.pubkey).enabled) {
                            _messages.tryEmit("Account or list mode changed — nothing published")
                            return@onSuccess
                        }
                        val targets = account.effectiveRelays().map(RelayPool::normalize).toSet()
                        if (pool.publishTo(event, targets) == 0) {
                            _messages.tryEmit("No connected relays — nothing published")
                            return@onSuccess
                        }
                        followMutations.published(account.pubkey, event)
                        accountRepository.updateAccount(account.pubkey) {
                            it.copy(
                                follows = newEntries.map { e -> e.pubkey },
                                followEntries = newEntries,
                            )
                        }
                    }
                    .onFailure { e -> Timber.w(e, "toggleFollow failed") }
            } finally {
                _isFollowLoading.update { false }
            }
            } finally { followMutations.finish(mutationJob) }
          }
        }
    }

    override fun onCleared() {
        quoteLookup.close()
        notesSubId?.let { pool.unsubscribe(it) }
        allSubIds.forEach { pool.unsubscribe(it) }
    }

    companion object {
        /** Notes/reposts/polls requested per profile page. */
        private const val PROFILE_FEED_LIMIT = 150

        /** Upper bound on retained profile events. */
        private const val MAX_FEED_EVENTS = 400
    }
}
