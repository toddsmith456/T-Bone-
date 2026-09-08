package social.tbone.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import social.tbone.Tunables
import javax.inject.Inject

data class ProfileMatch(val pubkey: String, val profile: ProfileContent?)

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data class NpubInput(val pubkey: String, val profile: ProfileContent?) : SearchUiState
    data class HashtagInput(val tag: String) : SearchUiState
    data object Searching : SearchUiState
    data class Results(val profiles: List<ProfileMatch>) : SearchUiState
}

@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val pool: RelayPool,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _uiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchRelayAdded = false

    init {
        viewModelScope.launch {
            _query.debounce(300).collectLatest { q -> processQuery(q.trim()) }
        }
    }

    fun onQueryChange(q: String) = _query.update { q }

    private suspend fun processQuery(q: String) {
        // Accept a bare npub1…/nprofile1… or a full "nostr:npub1…" URI pasted in.
        val clean = q.removePrefix("nostr:").removePrefix("NOSTR:").trim()
        when {
            q.isEmpty() -> _uiState.update { SearchUiState.Idle }

            (clean.startsWith("npub1") || clean.startsWith("nprofile1")) && clean.length > 20 -> {
                val hex = if (clean.startsWith("npub1")) {
                    Nip19.npubToHex(clean)
                } else {
                    Nip19.nprofileToHex(clean)
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

    /**
     * Subscribes for a single kind-0 metadata event from the given pubkey on the
     * search relay, and refreshes the NpubInput state when it arrives. Times out
     * after [Tunables.SEARCH_EOSE_TIMEOUT_MS] so we never leak a subscription.
     */
    private fun fetchProfileForPubkey(hex: String) {
        if (!searchRelayAdded) {
            pool.acquireRelay(Tunables.SEARCH_RELAY)
            searchRelayAdded = true
        }
        val subId = pool.subscribe(listOf(
            Filter(kinds = listOf(EventKind.METADATA), authors = listOf(hex), limit = 1)
        ))
        viewModelScope.launch {
            try {
                withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                    pool.messages.collect { (_, msg) ->
                        when (msg) {
                            is RelayMessage.EventMessage -> {
                                if (msg.subscriptionId == subId && msg.event.verify() &&
                                    msg.event.pubkey == hex
                                ) {
                                    profileRepository.processEvent(msg.event)
                                    val fresh = profileRepository.getProfile(hex)
                                    _uiState.update { state ->
                                        if (state is SearchUiState.NpubInput && state.pubkey == hex) {
                                            state.copy(profile = fresh)
                                        } else state
                                    }
                                }
                            }
                            is RelayMessage.EndOfStoredEvents -> {
                                if (msg.subscriptionId == subId) {
                                    pool.unsubscribe(subId)
                                    currentCoroutineContext().cancel()
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    private suspend fun searchProfiles(query: String) {
        // Seed with local cache matches immediately
        val local = profileRepository.profiles.value
            .filter { (_, p) ->
                p.bestName?.contains(query, ignoreCase = true) == true ||
                p.nip05?.contains(query, ignoreCase = true) == true
            }
            .map { (pubkey, p) -> ProfileMatch(pubkey, p) }
            .sortedBy { it.profile?.bestName }
            .take(20)

        _uiState.update { SearchUiState.Results(local) }

        // Connect to the search relay once per screen visit; released in onCleared.
        if (!searchRelayAdded) {
            pool.acquireRelay(Tunables.SEARCH_RELAY)
            searchRelayAdded = true
        }
        val subId = pool.subscribe(listOf(
            Filter(kinds = listOf(EventKind.METADATA), search = query, limit = 20)
        ))

        try {
            // EOSE timeout: unsubscribe after 10 s even if the relay never sends EOSE.
            withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                pool.messages.collect { (_, msg) ->
                    when (msg) {
                        is RelayMessage.EventMessage -> {
                            if (msg.subscriptionId == subId && msg.event.verify()) {
                                profileRepository.processEvent(msg.event)
                                val pubkey = msg.event.pubkey
                                val current = (_uiState.value as? SearchUiState.Results)?.profiles ?: local
                                if (current.none { it.pubkey == pubkey }) {
                                    val updated = (current + ProfileMatch(pubkey, profileRepository.getProfile(pubkey)))
                                        .sortedBy { it.profile?.bestName }
                                    _uiState.update { SearchUiState.Results(updated) }
                                }
                            }
                        }
                        is RelayMessage.EndOfStoredEvents -> {
                            if (msg.subscriptionId == subId) {
                                pool.unsubscribe(subId)
                                currentCoroutineContext().cancel()
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } finally {
            pool.unsubscribe(subId)
        }
    }

    override fun onCleared() {
        if (searchRelayAdded) {
            pool.releaseRelay(Tunables.SEARCH_RELAY)
            searchRelayAdded = false
        }
    }
}
