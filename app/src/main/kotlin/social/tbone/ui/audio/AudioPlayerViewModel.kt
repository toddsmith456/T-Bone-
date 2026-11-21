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

enum class AudioCategory(val tag: String, val hashtag: String, val label: String) {
    MUSIC("tbonemusic", "TboneMusic", "Music"),
    PODCASTS("tbonepodcast", "TbonePodcast", "Podcasts");
}
enum class AudioAudience { FOLLOWS, GLOBAL }
enum class AudioPlayerTab { PLAYER, LIBRARY, DISCOVER }

data class AudioPlayerUiState(
    val category: AudioCategory = AudioCategory.MUSIC,
    val audience: AudioAudience = AudioAudience.GLOBAL,
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
    private val metadataCache: social.tbone.media.audio.AudioMetadataCache,
    private val settings: social.tbone.settings.AppSettings,
    private val offlineLists: social.tbone.lists.OfflineListRepository,
    private val accounts: social.tbone.account.AccountRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val bookmarkSyncRepository: BookmarkSyncRepository,
    private val pool: RelayPool,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val muteRepository: social.tbone.lists.MuteListRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AudioPlayerUiState())
    val uiState: StateFlow<AudioPlayerUiState> = _uiState.asStateFlow()

    private val _metadata = MutableStateFlow<Map<String, social.tbone.media.audio.AudioMetadataReader.Metadata>>(emptyMap())
    val metadata = _metadata.asStateFlow()
    private val requestedMetadata = mutableSetOf<String>()
    fun loadMetadata(url: String, allowNetwork: Boolean = true) {
        if (!requestedMetadata.add(url)) return
        viewModelScope.launch {
            val generation = metadataCache.generation.value
            fun publish(result: social.tbone.media.audio.AudioMetadataReader.Metadata?) {
                if (result == null || generation != metadataCache.generation.value) return
                _metadata.update { current -> (current.filterKeys { it != url } + (url to result))
                    .entries.toList().takeLast(24).associate { it.key to it.value } }
            }
            try {
                publish(metadataCache.read(url, allowNetwork = false))
                if (allowNetwork) publish(metadataCache.read(url))
            } finally { requestedMetadata.remove(url) }
        }
    }

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    private var follows: Set<String> = emptySet()
    private var accountPubkey: String? = null
    private var reloadJob: Job? = null
    @Volatile private var feedGeneration = 0L
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
        viewModelScope.launch {
            accounts.activeAccount.flatMapLatest { account ->
                if (account == null) flowOf(null to emptySet<String>())
                else offlineLists.state(social.tbone.lists.ListType.FOLLOWS, account.pubkey).map { local ->
                    account.pubkey to audioFollowSet(
                        (account.follows + account.followEntries.map { it.pubkey }).toSet(),
                        local.enabled, local.pubkeySet(),
                    )
                }
            }.distinctUntilChanged().collect { (pubkey, followed) ->
                accountPubkey = pubkey
                follows = followed
                refreshMusicFeed()
            }
        }
        viewModelScope.launch { settings.cachePreset.collect { metadataCache.trim() } }
        viewModelScope.launch {
            metadataCache.generation.collect { _metadata.value = emptyMap(); requestedMetadata.clear() }
        }
    }

    fun selectCategory(category: AudioCategory) {
        if (_uiState.value.category == category) return
        _uiState.update { it.copy(category = category) }
        refreshMusicFeed()
    }
    fun selectAudience(audience: AudioAudience) {
        if (_uiState.value.audience == audience) return
        _uiState.update { it.copy(audience = audience) }
        refreshMusicFeed()
    }
    suspend fun resolveMetadata(url: String) = metadataCache.read(url)

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
        if (cleanName.length > 48 - AudioPlaylists.TITLE_PREFIX.length) {
            _uiState.update { it.copy(statusMessage = "Playlist name is too long (including its music prefix)") }
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
        feedGeneration++
        val generation = feedGeneration
        reloadJob?.cancel()
        feedCollectionJob?.cancel()
        feedTimeoutJob?.cancel()
        feedSubscriptionId?.let(pool::unsubscribe)
        feedSubscriptionId = null
        val category = _uiState.value.category
        val audience = _uiState.value.audience
        val authors = follows.toSet()
        _uiState.update { it.copy(discoveredEvents = emptyList(), isLoadingFeed = true) }
        reloadJob = viewModelScope.launch {
            val cached = kotlinx.coroutines.withContext(Dispatchers.IO) { eventRepository.recentAudioCandidates() }
                .filter { matchesAudioFeed(it, category, audience, authors) }
                .filterNot { it.pubkey in muteRepository.effectiveMuted.value }
                .take(100)
            if (generation != feedGeneration) return@launch
            _uiState.update { it.copy(discoveredEvents = cached) }
            profileRepository.requestProfiles(cached.map { it.pubkey }.distinct())
            // Never let an empty authors filter accidentally become a global query.
            if (audience == AudioAudience.FOLLOWS && authors.isEmpty()) {
                _uiState.update { it.copy(isLoadingFeed = false) }
                return@launch
            }
            val filters = if (audience == AudioAudience.GLOBAL) listOf(Filter(
                kinds = listOf(EventKind.TEXT_NOTE), tTags = listOf(category.tag), limit = 100,
            )) else authors.chunked(200).map { chunk -> Filter(
                authors = chunk, kinds = listOf(EventKind.TEXT_NOTE), tTags = listOf(category.tag), limit = 100,
            ) }
            val id = pool.subscribe(filters = filters, label = "audio-${category.tag}-${audience.name.lowercase()}")
            feedSubscriptionId = id
            feedCollectionJob = viewModelScope.launch(Dispatchers.Default) {
                pool.messages.collect { (_, message) ->
                    if (generation != feedGeneration) return@collect
                    when (message) {
                        is RelayMessage.EventMessage -> {
                            if (message.subscriptionId != id) return@collect
                            val event = message.event
                            if (!event.verify() || !matchesAudioFeed(event, category, audience, authors)) return@collect
                            if (event.pubkey in muteRepository.effectiveMuted.value) return@collect
                            eventRepository.saveQueued(event, "")
                            _uiState.update { state ->
                                if (state.category != category || state.audience != audience || generation != feedGeneration) state
                                else state.copy(discoveredEvents = (state.discoveredEvents + event)
                                    .distinctBy(Event::id).sortedByDescending(Event::createdAt).take(100))
                            }
                            profileRepository.requestProfiles(listOf(event.pubkey))
                        }
                        is RelayMessage.EndOfStoredEvents -> if (message.subscriptionId == id) {
                            _uiState.update { if (generation == feedGeneration) it.copy(isLoadingFeed = false) else it }
                        }
                        else -> Unit
                    }
                }
            }
            feedTimeoutJob = viewModelScope.launch {
                delay(FEED_TIMEOUT_MS)
                if (generation == feedGeneration) _uiState.update { it.copy(isLoadingFeed = false) }
            }
        }
    }

    override fun onCleared() {
        reloadJob?.cancel()
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

internal fun matchesAudioFeed(event: Event, category: AudioCategory, audience: AudioAudience, follows: Set<String>): Boolean =
    event.kind == EventKind.TEXT_NOTE &&
        event.parsedTags.any { it.name == "t" && it.value()?.equals(category.tag, true) == true } &&
        (audience == AudioAudience.GLOBAL || event.pubkey in follows) && audioUrls(event).isNotEmpty()

internal fun audioFollowSet(online: Set<String>, offlineEnabled: Boolean, offline: Set<String>): Set<String> =
    if (offlineEnabled) offline else online
