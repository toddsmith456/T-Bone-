package social.tbone.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.db.EventRepository
import social.tbone.notes.BookmarksRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import social.tbone.profile.ProfileSearch
import social.tbone.ui.feed.EventOpenCoordinator
import javax.inject.Inject

data class ProfileMatch(val pubkey: String, val profile: ProfileContent?)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class EventInput(
        val eventId: String,
        val event: Event? = null,
        val loading: Boolean = true,
        val error: String? = null,
    ) : SearchUiState
    data class NpubInput(val pubkey: String, val profile: ProfileContent?) : SearchUiState
    data class HashtagInput(val tag: String) : SearchUiState
    data object Searching : SearchUiState
    data class Results(
        val profiles: List<ProfileMatch>,
        val query: String = "",
        val isSearching: Boolean = false,
    ) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val pool: RelayPool,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val eventOpenCoordinator: EventOpenCoordinator,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchRelayAdded = false
    private var eventLookupJob: Job? = null
    private var profileLookupJob: Job? = null

    init {
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> processQuery(q.trim()) }
        }
    }

    fun onQueryChange(q: String) = _query.update { q }

    private suspend fun processQuery(q: String) {
        eventLookupJob?.cancel()
        eventLookupJob = null
        profileLookupJob?.cancel()
        profileLookupJob = null
        if (q.isEmpty()) {
            _uiState.update { SearchUiState.Idle }
            return
        }

        // Accept bare note/nevent references, nostr: URIs, and raw 32-byte hex IDs.
        val clean = q.trim().removePrefix("nostr:").removePrefix("NOSTR:").trim()
        val normalized = clean.lowercase()
        val looksLikeEvent = normalized.startsWith("note1") || normalized.startsWith("nevent1") ||
            normalized.matches(Regex("[0-9a-f]{64}"))
        if (looksLikeEvent) {
            val eventId = when {
                normalized.matches(Regex("[0-9a-f]{64}")) -> normalized
                normalized.startsWith("nevent1") -> Nip19.neventToHex(normalized)
                else -> Nip19.noteToHex(normalized)
            }
            if (eventId == null) {
                _uiState.update {
                    SearchUiState.EventInput(clean, event = null, loading = false, error = "That note/nevent reference is invalid.")
                }
            } else {
                resolveEvent(eventId)
            }
            return
        }

        when {
            (normalized.startsWith("npub1") || normalized.startsWith("nprofile1")) && normalized.length > 20 -> {
                val hex = if (normalized.startsWith("npub1")) {
                    Nip19.npubToHex(normalized)
                } else {
                    Nip19.nprofileToHex(normalized)
                }
                if (hex != null) {
                    _uiState.update { SearchUiState.NpubInput(hex, profileRepository.getProfile(hex)) }
                    // Fetch the profile metadata from relays so a pasted npub of
                    // someone we've never seen still resolves to a real user.
                    fetchProfileForPubkey(hex)
                } else {
                    _uiState.update { SearchUiState.Idle }
                }
            }

            q.startsWith("#") && q.length > 1 -> {
                val tag = q.removePrefix("#").lowercase().trim()
                if (tag.isNotEmpty()) _uiState.update { SearchUiState.HashtagInput(tag) }
            }

            q.length >= 2 -> searchProfiles(q)

            else -> _uiState.update { SearchUiState.Idle }
        }
    }

    private fun resolveEvent(eventId: String) {
        _uiState.update { SearchUiState.EventInput(eventId = eventId, loading = true) }
        eventLookupJob = viewModelScope.launch {
            val local = runCatching { eventRepository.getById(eventId) }.getOrNull()
                ?: runCatching { bookmarksRepository.findEvent(eventId) }.getOrNull()
            if (local != null && local.verify()) {
                _uiState.update { SearchUiState.EventInput(eventId, event = local, loading = false) }
                return@launch
            }

            ensureSearchRelay()
            val subId = pool.subscribeTo(
                listOf(Filter(ids = listOf(eventId), limit = 1)),
                label = "search-event",
                relays = setOf(Tunables.SEARCH_RELAY),
            )
            try {
                val terminal = withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                    pool.messages.first { (relayUrl, message) ->
                        relayUrl == Tunables.SEARCH_RELAY && when (message) {
                            is RelayMessage.EventMessage -> message.subscriptionId == subId &&
                                message.event.id.equals(eventId, ignoreCase = true) && message.event.verify()
                            is RelayMessage.EndOfStoredEvents -> message.subscriptionId == subId
                            else -> false
                        }
                    }
                }
                val message = terminal?.message
                when (message) {
                    is RelayMessage.EventMessage ->
                        _uiState.update { SearchUiState.EventInput(eventId, event = message.event, loading = false) }
                    is RelayMessage.EndOfStoredEvents ->
                        _uiState.update { SearchUiState.EventInput(eventId, loading = false, error = "No matching event was returned by the search relay.") }
                    else ->
                        _uiState.update { SearchUiState.EventInput(eventId, loading = false, error = "Timed out while searching for this event.") }
                }
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    fun openInMainFeed(event: Event) = eventOpenCoordinator.openInMainFeed(event)

    /** Fetch one metadata event for a pasted npub, scoped to the search relay. */
    private fun fetchProfileForPubkey(hex: String) {
        ensureSearchRelay()
        val subId = pool.subscribeTo(
            listOf(Filter(kinds = listOf(EventKind.METADATA), authors = listOf(hex), limit = 1)),
            label = "search-profile-by-pubkey",
            relays = setOf(Tunables.SEARCH_RELAY),
        )
        profileLookupJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                    pool.messages.takeWhile { (relayUrl, msg) ->
                        if (relayUrl != Tunables.SEARCH_RELAY) return@takeWhile true
                        when (msg) {
                            is RelayMessage.EventMessage -> {
                                if (msg.subscriptionId == subId && msg.event.kind == EventKind.METADATA &&
                                    msg.event.verify() && msg.event.pubkey == hex
                                ) {
                                    profileRepository.processEvent(msg.event)
                                    val fresh = profileRepository.getProfile(hex)
                                    _uiState.update { state ->
                                        if (state is SearchUiState.NpubInput && state.pubkey == hex) {
                                            state.copy(profile = fresh)
                                        } else state
                                    }
                                }
                                true
                            }
                            is RelayMessage.EndOfStoredEvents -> msg.subscriptionId != subId
                            else -> true
                        }
                    }.collect()
                }
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    private suspend fun searchProfiles(query: String) {
        val normalizedQuery = ProfileSearch.normalize(query)
        val previouslyVisible = (_uiState.value as? SearchUiState.Results)
            ?.profiles
            .orEmpty()
            .filter { match -> match.profile?.let { ProfileSearch.matches(query, it) } == true }
        val cachedMatches = profileRepository.profiles.value
            .filter { (_, profile) -> ProfileSearch.matches(query, profile) }
            .map { (pubkey, profile) -> ProfileMatch(pubkey, profile) }

        _uiState.update {
            SearchUiState.Results(
                profiles = mergeMatches(query, previouslyVisible + cachedMatches),
                query = normalizedQuery,
                isSearching = true,
            )
        }

        ensureSearchRelay()
        val filters = ProfileSearch.relayQueryVariants(query).map { relayQuery ->
            Filter(kinds = listOf(EventKind.METADATA), search = relayQuery, limit = RELAY_PROFILE_LIMIT)
        }
        val subId = pool.subscribeTo(
            filters,
            label = "search-profiles",
            relays = setOf(Tunables.SEARCH_RELAY),
        )
        val cacheMonitor = viewModelScope.launch(Dispatchers.Default) {
            // ProfileRepository loads its persisted cache asynchronously. Keep
            // this active query in sync so a late cache load cannot leave an
            // empty or incomplete relay result on screen.
            profileRepository.profiles.collect { profiles ->
                if (_query.value.trim() != query) return@collect
                val freshMatches = profiles
                    .filter { (_, profile) -> ProfileSearch.matches(query, profile) }
                    .map { (pubkey, profile) -> ProfileMatch(pubkey, profile) }
                _uiState.update { state ->
                    if (state is SearchUiState.Results && state.query == normalizedQuery) {
                        state.copy(profiles = mergeMatches(query, state.profiles + freshMatches))
                    } else state
                }
            }
        }

        try {
            // Complete on EOSE or timeout. A result list stays visible while the
            // relay is queried, and every event is merged into—not substituted
            // for—the cached list so late relay responses cannot make matches blink.
            withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                pool.messages.takeWhile { (relayUrl, msg) ->
                    if (relayUrl != Tunables.SEARCH_RELAY) return@takeWhile true
                    when (msg) {
                        is RelayMessage.EventMessage -> {
                            if (msg.subscriptionId == subId &&
                                msg.event.kind == EventKind.METADATA &&
                                msg.event.verify() && _query.value.trim() == query
                            ) {
                                val incoming = ProfileContent.parse(msg.event.content)
                                if (incoming != null && ProfileSearch.matches(query, incoming)) {
                                    profileRepository.processEvent(msg.event)
                                    val fresh = profileRepository.getProfile(msg.event.pubkey) ?: incoming
                                    if (ProfileSearch.matches(query, fresh)) {
                                        _uiState.update { state ->
                                            if (state is SearchUiState.Results && state.query == normalizedQuery) {
                                                state.copy(
                                                    profiles = mergeMatches(
                                                        query,
                                                        state.profiles + ProfileMatch(msg.event.pubkey, fresh),
                                                    ),
                                                    isSearching = true,
                                                )
                                            } else state
                                        }
                                    }
                                }
                            }
                            true
                        }
                        is RelayMessage.EndOfStoredEvents -> {
                            if (msg.subscriptionId == subId) {
                                markSearchFinished(normalizedQuery)
                                false
                            } else true
                        }
                        else -> true
                    }
                }.collect()
            }
        } finally {
            cacheMonitor.cancel()
            pool.unsubscribe(subId)
            if (_query.value.trim() == query) markSearchFinished(normalizedQuery)
        }
    }

    private fun mergeMatches(query: String, matches: List<ProfileMatch>): List<ProfileMatch> =
        matches.asSequence()
            .filter { it.profile != null && ProfileSearch.matches(query, it.profile) }
            .associateBy { it.pubkey }
            .values
            .sortedWith(
                compareBy<ProfileMatch> { ProfileSearch.normalize(it.profile?.bestName ?: "") }
                    .thenBy { it.pubkey },
            )
            .take(MAX_PROFILE_RESULTS)

    private fun markSearchFinished(normalizedQuery: String) {
        _uiState.update { state ->
            if (state is SearchUiState.Results && state.query == normalizedQuery) {
                state.copy(isSearching = false)
            } else state
        }
    }

    private fun ensureSearchRelay() {
        if (!searchRelayAdded) {
            pool.acquireRelay(Tunables.SEARCH_RELAY)
            searchRelayAdded = true
        }
    }

    override fun onCleared() {
        eventLookupJob?.cancel()
        profileLookupJob?.cancel()
        if (searchRelayAdded) {
            pool.releaseRelay(Tunables.SEARCH_RELAY)
            searchRelayAdded = false
        }
    }

    private companion object {
        const val RELAY_PROFILE_LIMIT = 20
        const val MAX_PROFILE_RESULTS = 50
    }
}
