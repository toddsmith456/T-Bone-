package social.tbone.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.db.EventRepository
import social.tbone.media.audio.AudioPlaylists
import social.tbone.media.audio.audioUrls
import social.tbone.notes.BookmarkFolderUi
import social.tbone.notes.BookmarkSyncRepository
import social.tbone.notes.BookmarkUi
import social.tbone.notes.BookmarksRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.profile.ProfileRepository
import javax.inject.Inject

enum class AudioPlayerTab { PLAYER, LIBRARY, DISCOVER }

data class AudioPlayerUiState(
    val playlists: List<BookmarkFolderUi> = emptyList(),
    val selectedPlaylistId: String? = null,
    val playlistTracks: List<BookmarkUi> = emptyList(),
    val discoveredEvents: List<Event> = emptyList(),
    val isLoadingFeed: Boolean = true,
    val isSyncingPlaylists: Boolean = false,
    val isCreatingPlaylist: Boolean = false,
    val statusMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AudioPlayerViewModel @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    private val bookmarkSyncRepository: BookmarkSyncRepository,
    private val pool: RelayPool,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val muteRepository: social.tbone.lists.MuteListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AudioPlayerUiState())
    val uiState: StateFlow<AudioPlayerUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    private var feedSubscriptionId: String? = null
    private var feedCollectionJob: Job? = null
    private var feedTimeoutJob: Job? = null
    private val pendingFeedEvents = mutableListOf<Event>()
    @Volatile private var feedSettled = false

    init {
        viewModelScope.launch {
            bookmarksRepository.folders.collect { folders ->
                val playlists = folders.filter(AudioPlaylists::isPlaylist)
                    .sortedBy { AudioPlaylists.displayName(it.name).lowercase() }
                _uiState.update { current ->
                    val selectedId = current.selectedPlaylistId
                        ?.takeIf { id -> playlists.any { it.id == id } }
                    current.copy(playlists = playlists, selectedPlaylistId = selectedId)
                }
            }
        }

        viewModelScope.launch {
            _uiState.map { it.selectedPlaylistId }
                .distinctUntilChanged()
                .flatMapLatest { folderId ->
                    if (folderId.isNullOrBlank()) flowOf(emptyList())
                    else bookmarksRepository.observeBookmarks(folderId)
                }
                .collect { tracks -> _uiState.update { it.copy(playlistTracks = tracks) } }
        }

        refreshPlaylists()
        subscribeToMusicFeed()
    }

    fun selectPlaylist(id: String?) {
        _uiState.update { it.copy(selectedPlaylistId = id, playlistTracks = emptyList()) }
    }

    fun clearStatus() { _uiState.update { it.copy(statusMessage = null) } }

    fun refreshPlaylists() {
        if (_uiState.value.isSyncingPlaylists) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncingPlaylists = true) }
            val result = runCatching { bookmarkSyncRepository.sync() }
            _uiState.update {
                it.copy(
                    isSyncingPlaylists = false,
                    statusMessage = result.fold(
                        onSuccess = { "${it.message} · online playlists sync with bookmarks; offline playlists stay on this device" },
                        onFailure = { error -> "Playlist sync failed: ${error.message ?: "unknown error"}" },
                    ),
                )
            }
        }
    }

    fun createPlaylist(name: String, online: Boolean = true) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            _uiState.update { it.copy(statusMessage = "Enter a playlist name") }
            return
        }
        if (cleanName.length > 64) {
            _uiState.update { it.copy(statusMessage = "Playlist names can be at most 64 characters") }
            return
        }
        if (_uiState.value.isCreatingPlaylist) return
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingPlaylist = true) }
            val result = runCatching {
                if (online) bookmarkSyncRepository.createOnlineFolder(AudioPlaylists.title(cleanName))
                else {
                    bookmarksRepository.createFolder(AudioPlaylists.title(cleanName))
                    null
                }
            }
            val outcome = result.getOrNull()
            _uiState.update {
                it.copy(
                    isCreatingPlaylist = false,
                    statusMessage = when {
                        result.isFailure -> "Couldn't create playlist: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                         !online -> "Created device-only playlist “$cleanName”"
                        outcome?.ok == true -> "Created playlist “$cleanName” and published its NIP-51 bookmark set"
                        else -> outcome?.message ?: "Couldn't create playlist"
                    },
                )
            }
        }
    }

    fun addEventToPlaylist(event: Event, playlistId: String) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        viewModelScope.launch {
            val result = runCatching {
                if (playlist.isOnline) bookmarkSyncRepository.addToOnlineFolder(playlist, event.id)
                else { bookmarksRepository.addBookmark(event, playlist.id); null }
            }
            val outcome = result.getOrNull()
            _uiState.update {
                it.copy(statusMessage = when {
                    result.isFailure -> "Couldn't add the song: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    !playlist.isOnline -> "Playlist updated on this device"
                    outcome?.ok == true -> outcome.message
                    else -> outcome?.message ?: "Couldn't add the song"
                })
            }
        }
    }

    fun removeEventFromPlaylist(eventId: String) {
        val playlist = _uiState.value.playlists.firstOrNull { it.id == _uiState.value.selectedPlaylistId } ?: return
        viewModelScope.launch {
            val result = runCatching {
                if (playlist.isOnline) bookmarkSyncRepository.removeFromOnlineFolder(playlist, eventId)
                else {
                    bookmarksRepository.getBookmarks(playlist.id).filter { it.event.id == eventId }
                        .forEach { bookmarksRepository.deleteBookmark(it.id) }
                    null
                }
            }
            val outcome = result.getOrNull()
            _uiState.update {
                it.copy(statusMessage = when {
                    result.isFailure -> "Couldn't remove the song: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    !playlist.isOnline -> "Playlist updated on this device"
                    outcome?.ok == true -> outcome.message
                    else -> outcome?.message ?: "Couldn't remove the song"
                })
            }
        }
    }

    fun refreshMusicFeed() {
        feedCollectionJob?.cancel()
        feedTimeoutJob?.cancel()
        feedSubscriptionId?.let(pool::unsubscribe)
        feedSubscriptionId = null
        feedSettled = false
        synchronized(pendingFeedEvents) { pendingFeedEvents.clear() }
        _uiState.update { it.copy(discoveredEvents = emptyList(), isLoadingFeed = true) }
        subscribeToMusicFeed()
    }

    private fun subscribeToMusicFeed() {
        val id = pool.subscribe(
            filters = listOf(
                Filter(
                    kinds = listOf(EventKind.TEXT_NOTE),
                    tTags = listOf(MUSIC_TAG),
                    limit = 100,
                ),
            ),
            label = "audio-player-tbonemusic",
        )
        feedSubscriptionId = id

        feedCollectionJob = viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                when (message) {
                    is RelayMessage.EventMessage -> {
                        if (message.subscriptionId != id) return@collect
                        val event = message.event
                        if (!event.verify() || event.kind != EventKind.TEXT_NOTE) return@collect
                        if (event.pubkey in muteRepository.effectiveMuted.value) return@collect
                        if (!event.parsedTags.any { it.name == "t" && it.value()?.equals(MUSIC_TAG, true) == true }) return@collect
                        if (audioUrls(event).isEmpty()) return@collect
                        eventRepository.saveQueued(event, "")
                        if (!feedSettled) {
                            synchronized(pendingFeedEvents) { pendingFeedEvents += event }
                        } else {
                            addFeedEvents(listOf(event))
                        }
                        profileRepository.requestProfiles(listOf(event.pubkey))
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        if (message.subscriptionId != id) return@collect
                        feedSettled = true
                        flushFeedEvents()
                        _uiState.update { it.copy(isLoadingFeed = false) }
                    }
                    else -> Unit
                }
            }
        }

        feedTimeoutJob = viewModelScope.launch {
            delay(FEED_TIMEOUT_MS)
            if (feedSubscriptionId == id && !feedSettled) {
                feedSettled = true
                flushFeedEvents()
                _uiState.update { it.copy(isLoadingFeed = false) }
            }
        }
    }

    private fun flushFeedEvents() {
        val batch = synchronized(pendingFeedEvents) {
            pendingFeedEvents.toList().also { pendingFeedEvents.clear() }
        }
        if (batch.isNotEmpty()) addFeedEvents(batch)
    }

    private fun addFeedEvents(events: List<Event>) {
        _uiState.update { state ->
            state.copy(discoveredEvents = (state.discoveredEvents + events)
                .distinctBy(Event::id)
                .sortedByDescending(Event::createdAt))
        }
    }

    override fun onCleared() {
        feedCollectionJob?.cancel()
        feedTimeoutJob?.cancel()
        feedSubscriptionId?.let(pool::unsubscribe)
        super.onCleared()
    }

    companion object {
        const val MUSIC_TAG = "tbonemusic"
        private const val FEED_TIMEOUT_MS = 12_000L
    }
}
