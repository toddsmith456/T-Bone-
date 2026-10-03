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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
import social.tbone.ui.feed.extractInlineQuoteId
import social.tbone.ui.feed.parseNoteContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Profile feed tabs. */
enum class ProfileTab(val label: String) {
    ALL("ALL"),
    REPLIES("REPLIES"),
    MEDIA("MEDIA"),
}

/** One image in the profile's media gallery. */
data class ProfileImage(val url: String, val noteId: String)

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
) : ViewModel() {

    val pubkey: String = checkNotNull(savedStateHandle["pubkey"])

    val profile: StateFlow<ProfileContent?> = profileRepository.profiles
        .map { it[pubkey] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), profileRepository.getProfile(pubkey))

    /** All known profiles — used to render quoted notes and mention names. */
    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _tab = MutableStateFlow(ProfileTab.ALL)
    /** Which feed the profile is showing. */
    val tab: StateFlow<ProfileTab> = _tab.asStateFlow()

    fun selectTab(newTab: ProfileTab) {
        if (_tab.value != newTab) _tab.value = newTab
    }

    /** Every event on the profile feed, newest first (notes, polls and reposts). */
    private val _notes = MutableStateFlow<List<Event>>(emptyList())
    val notes: StateFlow<List<Event>> = _notes.asStateFlow()

    /** Only the profile's replies — every note carrying a NIP-10 reply marker. */
    private val _replyNotes = MutableStateFlow<List<Event>>(emptyList())
    val replyNotes: StateFlow<List<Event>> = _replyNotes.asStateFlow()

    /** Every image in the profile's own notes, newest note first (media tab). */
    private val _mediaImages = MutableStateFlow<List<ProfileImage>>(emptyList())
    val mediaImages: StateFlow<List<ProfileImage>> = _mediaImages.asStateFlow()

    /** Quoted/reposted notes, resolved so the cards can render them. */
    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    /**
     * Referenced notes that were asked for and never arrived, so cards show a
     * terminal "not available" row instead of "loading…" forever.
     */
    private val _unresolvedQuoteIds = MutableStateFlow<Set<String>>(emptySet())
    val unresolvedQuoteIds: StateFlow<Set<String>> = _unresolvedQuoteIds.asStateFlow()

    private fun refreshUnresolvedQuotes() {
        val resolved = _quotedEvents.value.keys
        _unresolvedQuoteIds.value = requestedQuoteIds.filterTo(mutableSetOf()) { it !in resolved }
    }

    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val allSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
                MuteOutcome.NO_SIGNER -> _messages.tryEmit("No signer available — block list not changed")
                MuteOutcome.RELAYS_UNREACHABLE ->
                    _messages.tryEmit("Couldn't reach your relays to read your mute list — nothing changed")
                MuteOutcome.SIGN_FAILED -> _messages.tryEmit("Signing failed — block list not changed")
                MuteOutcome.NO_ACCOUNT -> Unit
                MuteOutcome.REJECTED -> _messages.tryEmit("Can't block that account")
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
                    EventKind.POLL,
                    EventKind.COMMENT,
                ),
                limit = PROFILE_FEED_LIMIT,
            ),
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.METADATA), limit = 1),
        ), label = "profile")
        notesSubId?.let { allSubIds.add(it) }

        viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                when (message) {
                    is RelayMessage.EventMessage -> {
                        val event = message.event
                        if (!event.verify()) return@collect
                        // Quote/repost targets resolved by a side subscription.
                        if (message.subscriptionId in quoteSubIds ||
                            event.id in requestedQuoteIds
                        ) {
                            handleQuotedEvent(event)
                            return@collect
                        }
                        when (event.kind) {
                            EventKind.METADATA -> profileRepository.processEvent(event)
                            EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL,
                            EventKind.COMMENT ->
                                if (event.pubkey == pubkey) {
                                    // Hide if the viewer blocked them / nsfw / hidden words.
                                    if (social.tbone.settings.ContentFilter.shouldHide(
                                            event,
                                            muteRepository.effectiveMuted.value,
                                            appSettings.hideNsfw.value,
                                            appSettings.hideWords.value,
                                        )
                                    ) {
                                        return@collect
                                    }
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
                        if (message.subscriptionId in quoteSubIds) {
                            // The first EOSE is only the fastest relay; keep the
                            // lookup open so slower relays can still deliver the
                            // quoted/reposted note, then close it.
                            val sid = message.subscriptionId
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(QUOTE_GRACE_MS)
                                if (quoteSubIds.remove(sid)) pool.unsubscribe(sid)
                                // Lookup finished: everything still missing is
                                // unavailable rather than still loading.
                                refreshUnresolvedQuotes()
                            }
                        }
                        _isLoading.update { false }
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            delay(10_000)
            _isLoading.update { false }
        }
    }

    /**
     * Adds an event to the profile feed and refreshes the three derived views:
     * all, replies-only, and the media gallery.
     */
    private fun ingest(event: Event) {
        val merged = (_notes.value + event)
            .distinctBy { it.id }
            .sortedByDescending { it.createdAt }
            .take(MAX_FEED_EVENTS)
        _notes.value = merged
        _replyNotes.value = merged.filter { event ->
            // NIP-22 comments (kind 1111) are replies by definition; plain notes
            // count when they carry a NIP-10 reply marker.
            event.kind == EventKind.COMMENT ||
                (event.kind == EventKind.TEXT_NOTE && event.parsedTags.isReply)
        }
        _mediaImages.value = merged
            .filter { it.kind == EventKind.TEXT_NOTE || it.kind == EventKind.COMMENT }
            .flatMap { note ->
                parseNoteContent(note.content).mediaItems
                    .filterIsInstance<MediaItem.Image>()
                    .map { image -> ProfileImage(url = image.url, noteId = note.id) }
            }
            .distinctBy { it.url }
    }

    private fun handleQuotedEvent(event: Event) {
        _quotedEvents.update { it + (event.id to event) }
        refreshUnresolvedQuotes()
        viewModelScope.launch { eventRepository.save(event, "") }
        fetchMetadataFor(listOf(event.pubkey))
    }

    /**
     * Resolves what the profile's notes point at: repost payloads (kind 6) and
     * quoted notes. Explicitly **kind-agnostic** — a quoted poll or repost used
     * to stay unresolved because only kind 1 was requested.
     */
    private fun fetchQuotesFor(events: List<Event>) {
        val toResolve = mutableListOf<String>()
        for (event in events) {
            when (event.kind) {
                EventKind.REPOST -> {
                    val embedded = if (event.content.length > MAX_REPOST_CONTENT_BYTES) null
                    else runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        refreshUnresolvedQuotes()
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                        fetchMetadataFor(listOf(embedded.pubkey))
                        if (embedded.kind != EventKind.REPOST) fetchQuotesFor(listOf(embedded))
                    } else {
                        event.parsedTags.firstOrNull { it.name == "e" }?.value()
                            ?.takeIf { it !in requestedQuoteIds }
                            ?.let { toResolve += it }
                    }
                }
                EventKind.TEXT_NOTE, EventKind.COMMENT -> {
                    (event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content))
                        ?.takeIf { it !in requestedQuoteIds }
                        ?.let { toResolve += it }
                }
                else -> Unit
            }
        }
        val missing = toResolve.distinct().filter { it !in _quotedEvents.value }
        if (missing.isEmpty()) return
        missing.forEach { requestedQuoteIds += it }

        viewModelScope.launch {
            val cached = eventRepository.getByIds(missing).associateBy { it.id }
            if (cached.isNotEmpty()) {
                _quotedEvents.update { it + cached }
                refreshUnresolvedQuotes()
                fetchMetadataFor(cached.values.map { it.pubkey })
            }
            val stillMissing = missing.filter { it !in cached }
            if (stillMissing.isEmpty()) return@launch
            val subId = pool.subscribe(listOf(Filter(ids = stillMissing)), label = "profile-quote")
            quoteSubIds += subId
        }
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
                val latest = runCatching {
                    listFetcher.fetchLatest(account.pubkey, EventKind.FOLLOW_LIST, account.relays)
                }.getOrNull()?.event

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

                // Keep whatever else the existing kind-3 carried (content, non-p tags).
                val keptTags = latest?.tags?.filter { (it.firstOrNull() as? JsonPrimitive)?.content != "p" }.orEmpty()
                val pTags = newEntries.map { entry ->
                    buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(entry.pubkey))
                        // NIP-02 positional fields: a petname needs the relay slot filled.
                        if (entry.relay.isNotEmpty() || entry.petname.isNotEmpty()) add(JsonPrimitive(entry.relay))
                        if (entry.petname.isNotEmpty()) add(JsonPrimitive(entry.petname))
                    }
                }
                val unsigned = UnsignedEvent(
                    pubkey = signer.pubkey,
                    // Replaceable event: must be strictly newer than the one it replaces.
                    createdAt = maxOf(System.currentTimeMillis() / 1000, (latest?.createdAt ?: 0L) + 1),
                    kind = EventKind.FOLLOW_LIST,
                    content = latest?.content ?: "",
                    tags = pTags + keptTags,
                )

                signer.signEvent(unsigned)
                    .onSuccess { event ->
                        pool.publish(event)
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
        }
    }

    override fun onCleared() {
        notesSubId?.let { pool.unsubscribe(it) }
        allSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.forEach { pool.unsubscribe(it) }
    }

    companion object {
        /** Notes/reposts/polls requested per profile page. */
        private const val PROFILE_FEED_LIMIT = 150

        /** Upper bound on retained profile events. */
        private const val MAX_FEED_EVENTS = 400

        private val MAX_REPOST_CONTENT_BYTES get() = social.tbone.Tunables.MAX_REPOST_CONTENT_BYTES
    }
}

/** How long a quote lookup stays open after the first relay EOSE. */
private const val QUOTE_GRACE_MS = 8_000L
