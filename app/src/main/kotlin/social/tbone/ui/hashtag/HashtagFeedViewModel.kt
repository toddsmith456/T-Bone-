package social.tbone.ui.hashtag

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.db.EventRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import social.tbone.ui.feed.extractInlineQuoteId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class HashtagFeedUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class HashtagFeedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val pool: RelayPool,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val muteRepository: social.tbone.lists.MuteListRepository,
) : ViewModel() {

    val hashtag: String = checkNotNull(savedStateHandle["tag"])

    private val _uiState = MutableStateFlow(HashtagFeedUiState())
    val uiState: StateFlow<HashtagFeedUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    /**
     * Quoted / reposted notes for the events on screen. Without this, hashtag
     * results showed "loading quoted note…" / "loading reposted note…" forever
     * — the quoted note was never looked up on this screen.
     */
    private val _quotedEvents = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quotedEvents: StateFlow<Map<String, Event>> = _quotedEvents.asStateFlow()

    /** Referenced notes that are gone from every relay (terminal state for cards). */
    private val _unresolvedQuoteIds = MutableStateFlow<Set<String>>(emptySet())
    val unresolvedQuoteIds: StateFlow<Set<String>> = _unresolvedQuoteIds.asStateFlow()

    private var subId: String? = null
    private val profileSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val pendingEvents = mutableListOf<Event>()
    @Volatile private var settled = false

    init {
        subId = pool.subscribe(listOf(
            Filter(kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST), tTags = listOf(hashtag), limit = 100)
        ))

        viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, msg) ->
                when (msg) {
                    is RelayMessage.EventMessage -> {
                        if (msg.subscriptionId in quoteSubIds) {
                            val referenced = msg.event
                            if (referenced.verify()) {
                                _quotedEvents.update { it + (referenced.id to referenced) }
                                refreshUnresolvedQuotes()
                                viewModelScope.launch { eventRepository.save(referenced, "") }
                                fetchProfileForAuthor(referenced.pubkey)
                            }
                            return@collect
                        }
                        if (msg.subscriptionId != subId) return@collect
                        val event = msg.event
                        if (!event.verify()) return@collect
                        // Muted / blocked authors never show up (offline or relay mute list).
                        if (event.pubkey in muteRepository.effectiveMuted.value) return@collect
                        viewModelScope.launch { eventRepository.save(event, "") }
                        if (!settled) {
                            synchronized(pendingEvents) { pendingEvents.add(event) }
                        } else {
                            addToFeed(event)
                        }
                        fetchProfileForAuthor(event.pubkey)
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        if (msg.subscriptionId in quoteSubIds) {
                            // Lookup settled: anything still missing is gone,
                            // not "loading".
                            quoteSubIds -= msg.subscriptionId
                            pool.unsubscribe(msg.subscriptionId)
                            refreshUnresolvedQuotes()
                            return@collect
                        }
                        if (msg.subscriptionId != subId) return@collect
                        if (!settled) {
                            settled = true
                            flush()
                        }
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            delay(15_000)
            if (!settled) { settled = true; flush() }
            _uiState.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    private fun flush() {
        val events = synchronized(pendingEvents) { pendingEvents.toList().also { pendingEvents.clear() } }
        if (events.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.events + events).distinctBy { it.id }.sortedByDescending { it.createdAt }
            state.copy(events = merged)
        }
        fetchReferencesFor(events)
    }

    private fun addToFeed(event: Event) {
        _uiState.update { state ->
            val updated = (state.events + event).distinctBy { it.id }.sortedByDescending { it.createdAt }
            state.copy(events = updated)
        }
        fetchReferencesFor(listOf(event))
    }

    private fun refreshUnresolvedQuotes() {
        val resolved = _quotedEvents.value.keys
        _unresolvedQuoteIds.value = requestedQuoteIds.filterTo(mutableSetOf()) { it !in resolved }
    }

    /**
     * Resolves what the hashtag results point at: repost payloads (kind 6/16)
     * and quoted notes (q tag or an inline `nostr:note1…` reference). Cache
     * first, then the relays, kind-agnostic like the rest of the app.
     */
    private fun fetchReferencesFor(events: List<Event>) {
        val toResolve = mutableListOf<String>()
        for (event in events) {
            when (event.kind) {
                EventKind.REPOST, GENERIC_REPOST -> {
                    val embedded = if (event.content.length > MAX_REPOST_CONTENT_BYTES) {
                        null
                    } else {
                        runCatching { Event.fromJson(event.content) }.getOrNull()
                    }
                    if (embedded != null && embedded.verify()) {
                        _quotedEvents.update { it + (embedded.id to embedded) }
                        refreshUnresolvedQuotes()
                        viewModelScope.launch { eventRepository.save(embedded, "") }
                        fetchProfileForAuthor(embedded.pubkey)
                    } else {
                        event.parsedTags.firstOrNull { it.name == "e" }?.value()
                            ?.takeIf { it !in requestedQuoteIds }
                            ?.let { toResolve += it }
                    }
                }
                EventKind.TEXT_NOTE, EventKind.COMMENT, EventKind.POLL -> {
                    (event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content))
                        ?.takeIf { it !in requestedQuoteIds }
                        ?.let { toResolve += it }
                }
            }
        }
        val missing = toResolve.distinct().filter { it !in _quotedEvents.value }
        if (missing.isEmpty()) return
        requestedQuoteIds += missing

        viewModelScope.launch {
            val cached = eventRepository.getByIds(missing).associateBy { it.id }
            if (cached.isNotEmpty()) {
                _quotedEvents.update { it + cached }
                refreshUnresolvedQuotes()
                cached.values.forEach { fetchProfileForAuthor(it.pubkey) }
            }
            val stillMissing = missing.filter { it !in cached }
            if (stillMissing.isEmpty()) return@launch
            stillMissing.chunked(MAX_FILTER_BATCH).forEach { batch ->
                val id = pool.subscribe(listOf(Filter(ids = batch)), label = "hashtag-quote")
                quoteSubIds += id
            }
        }
    }

    private fun fetchProfileForAuthor(pubkey: String) {
        if (profileRepository.profiles.value[pubkey] != null) return
        val id = pool.subscribe(listOf(Filter(authors = listOf(pubkey), kinds = listOf(EventKind.METADATA), limit = 1)))
        profileSubIds.add(id)
    }

    override fun onCleared() {
        subId?.let { pool.unsubscribe(it) }
        profileSubIds.forEach { pool.unsubscribe(it) }
        quoteSubIds.forEach { pool.unsubscribe(it) }
    }

    companion object {
        /** NIP-18 generic repost. */
        private const val GENERIC_REPOST = 16

        /** Ids per `ids` filter. */
        private const val MAX_FILTER_BATCH = 50

        private val MAX_REPOST_CONTENT_BYTES get() = social.tbone.Tunables.MAX_REPOST_CONTENT_BYTES
    }
}
