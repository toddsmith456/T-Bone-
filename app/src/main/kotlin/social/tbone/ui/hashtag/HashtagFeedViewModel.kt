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
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
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
) : ViewModel() {

    val hashtag: String = checkNotNull(savedStateHandle["tag"])

    private val _uiState = MutableStateFlow(HashtagFeedUiState())
    val uiState: StateFlow<HashtagFeedUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    private var subId: String? = null
    private val profileSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
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
                        if (msg.subscriptionId != subId) return@collect
                        val event = msg.event
                        if (!event.verify()) return@collect
                        viewModelScope.launch { eventRepository.save(event, "") }
                        if (!settled) {
                            synchronized(pendingEvents) { pendingEvents.add(event) }
                        } else {
                            addToFeed(event)
                        }
                        fetchProfileForAuthor(event.pubkey)
                    }
                    is RelayMessage.EndOfStoredEvents -> {
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
    }

    private fun addToFeed(event: Event) {
        _uiState.update { state ->
            val updated = (state.events + event).distinctBy { it.id }.sortedByDescending { it.createdAt }
            state.copy(events = updated)
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
    }
}
