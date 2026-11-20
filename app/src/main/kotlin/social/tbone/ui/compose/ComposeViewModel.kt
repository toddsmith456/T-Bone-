package social.tbone.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.Tunables
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
import social.tbone.lists.PublishedRelayLists
import social.tbone.lists.RelayListFetcher
import social.tbone.media.blossom.BlossomUploader
import social.tbone.media.MediaProcessor
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip88
import social.tbone.nostr.Nip19
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.quotedEventId
import social.tbone.notes.BookmarksRepository
import social.tbone.notes.BookmarkSyncRepository
import social.tbone.media.audio.AudioPlaylists
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.settings.WriteAccountSwitcherStyle
import social.tbone.ui.search.ProfileMatch
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class ComposeUiState(
    val isPublishing: Boolean = false,
    val error: String? = null,
    val replyTargetRequired: Boolean = false,
    val replyResolutionPending: Boolean = false,
    val replyToEvent: Event? = null,
    val quoteToEvent: Event? = null,
    /** Ordered folder quotes; serialized payloads stay encrypted in the vault. */
    val quoteFolderEvents: List<Event> = emptyList(),
    val initialText: String = "",
    // NIP-88 poll builder
    val pollEnabled: Boolean = false,
    val pollOptions: List<String> = listOf("", ""),
    val pollMultiple: Boolean = false,
    // Blossom media uploads
    val isUploading: Boolean = false,
    val uploadingKind: String? = null,
    /** Explicit server override chosen on the Write tab (null = follow Settings rules). */
    val blossomServer: String? = null,
    /** Label for the null (Settings rules) chip: "beaver", "default", or "random". */
    val blossomAutoLabel: String = "random",
    /** Available Blossom servers (local enabled pool + published Beaver Mode servers). */
    val blossomServers: List<String> = emptyList(),
    /** Audio attachments on the draft can optionally be added to one NIP-51 music playlist. */
    val hasUploadedAudio: Boolean = false,
    val uploadedAudioUrls: List<String> = emptyList(),
    val originalAudioUrls: List<String> = emptyList(),
    val audioPlaylists: List<social.tbone.notes.BookmarkFolderUi> = emptyList(),
    val selectedAudioPlaylistId: String? = null,
)

/**
 * One-shot composer effects. Navigation belongs to the UI, not a callback
 * captured inside a ViewModel coroutine; the buffered channel also survives a
 * short period with no collector (e.g. an external signer or a rotation).
 */
sealed interface ComposeEffect {
    data object Published : ComposeEffect
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val accountRepository: AccountRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val appSettings: AppSettings,
    private val bookmarkSyncRepository: BookmarkSyncRepository,
    private val blossomUploader: BlossomUploader,
    private val relayListFetcher: RelayListFetcher,
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private companion object {
        const val KEY_BLOSSOM_SERVER = "blossomServer"
    }

    private val replyToId: String? = savedStateHandle["replyToId"]
    private val quoteToId: String? = savedStateHandle["quoteToId"]
    private val quoteFolderId: String? = savedStateHandle["quoteFolderId"]
    private val draft: String? = savedStateHandle["draft"]
    private val audioPath: String? = savedStateHandle["audioPath"]

    private val _uiState = MutableStateFlow(
        ComposeUiState(
            replyTargetRequired = !replyToId.isNullOrBlank(),
            replyResolutionPending = !replyToId.isNullOrBlank(),
            blossomServer = savedStateHandle.get<String>(KEY_BLOSSOM_SERVER)
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotBlank() },
        ),
    )
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    /** Published kind-10063 Blossom servers when Beaver Mode is enabled. */
    private val publishedBlossomServers = MutableStateFlow<List<String>>(emptyList())

    // ── Active account (avatar switch + publish) ─────────────────────────────
    val accounts: StateFlow<List<Account>> = accountRepository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val profiles: StateFlow<Map<String, social.tbone.nostr.ProfileContent>> = profileRepository.profiles

    val accountSwitcherStyle: StateFlow<WriteAccountSwitcherStyle> = appSettings.writeAccountSwitcherStyle

    private val _switchingAccount = MutableStateFlow(false)
    val switchingAccount = _switchingAccount.asStateFlow()

    fun switchAccount(pubkey: String) {
        if (_switchingAccount.value) return
        _switchingAccount.value = true
        viewModelScope.launch {
            try {
                accountRepository.setActiveAccount(pubkey)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update { it.copy(error = error.message ?: "Could not switch account") }
            } finally {
                _switchingAccount.value = false
            }
        }
    }

    // Preview content builder — mirrors publish() finalContent logic without side effects.
    fun buildPreviewContent(trimmed: String): String {
        val explicitQuote = _uiState.value.quoteToEvent
        // Only notes the user explicitly chose to quote are quoted. A reply
        // never inherits a quote from the note it replies to.
        val quoteIds = (
            _uiState.value.quoteFolderEvents.map { it.id } +
            listOfNotNull(explicitQuote?.id)
            ).distinct()
        val missingRefs = quoteIds
            .map { id ->
                val quoted = _uiState.value.quoteFolderEvents.firstOrNull { it.id == id }
                    ?: explicitQuote?.takeIf { it.id == id }
                val reference = if (quoted != null) Nip19.hexToNevent(id, quoted.pubkey, quoted.kind) else Nip19.hexToNevent(id)
                "nostr:$reference"
            }
            .filterNot { trimmed.contains(it) }
        return if (missingRefs.isNotEmpty()) "$trimmed\n\n${missingRefs.joinToString("\n")}" else trimmed
    }

    private data class MediaUploadRequest(
        val uri: Uri,
        val kind: String,
        val preferredServer: String?,
        /** When true, audio must be copied byte-for-byte, ignoring both compression toggles. */
        val preserveOriginalAudio: Boolean = false,
    )

    /** Display hints measured at upload time, keyed by the uploaded URL. */
    private val uploadedMediaHints = java.util.concurrent.ConcurrentHashMap<String, UploadedMediaHint>()

    private val mediaUploadQueue = Channel<MediaUploadRequest>(Channel.UNLIMITED)
    private val uploadedUrlQueue = Channel<String>(Channel.UNLIMITED)
    private val uploadErrorQueue = Channel<String>(Channel.UNLIMITED)
    val uploadedMediaUrls = uploadedUrlQueue.receiveAsFlow()
    val uploadErrors = uploadErrorQueue.receiveAsFlow()
    private val uploadQueueLock = Any()
    private var pendingUploads = 0

    private val publishEffectQueue = Channel<ComposeEffect>(Channel.BUFFERED)
    val effects: Flow<ComposeEffect> = publishEffectQueue.receiveAsFlow()
    private val publishInFlight = AtomicBoolean(false)

    // ── @ mention autocomplete ────────────────────────────────────────────────
    /** The text currently being typed after "@" (null when no mention in progress). */
    private val _mentionQuery = MutableStateFlow<String?>(null)
    val mentionQuery: StateFlow<String?> = _mentionQuery.asStateFlow()

    private val _mentionSuggestions = MutableStateFlow<List<ProfileMatch>>(emptyList())
    val mentionSuggestions: StateFlow<List<ProfileMatch>> = _mentionSuggestions.asStateFlow()

    private val _mentionSearching = MutableStateFlow(false)
    val mentionSearching: StateFlow<Boolean> = _mentionSearching.asStateFlow()

    private var mentionSearchRelayAdded = false
    private var currentMentionSubId: String? = null

    init {
        viewModelScope.launch {
            for (request in mediaUploadQueue) uploadMedia(request)
        }
        viewModelScope.launch {
            bookmarksRepository.folders.collect { folders ->
                val playlists = folders.filter(AudioPlaylists::isPlaylist)
                    .sortedBy { AudioPlaylists.displayName(it.name).lowercase() }
                _uiState.update { state ->
                    state.copy(
                        audioPlaylists = playlists,
                        selectedAudioPlaylistId = state.selectedAudioPlaylistId
                            ?.takeIf { id -> playlists.any { it.id == id } },
                    )
                }
            }
        }

        audioPath?.takeIf { it.isNotBlank() }?.let { path ->
            val recording = File(path)
            if (recording.isFile) {
                attachMedia(Uri.fromFile(recording), "audio")
            } else {
                uploadErrorQueue.trySend("recording file is no longer available")
            }
        }

        viewModelScope.launch {
            // Pre-filled draft (e.g. "publish type 1" from the Notes toolbox).
            if (!draft.isNullOrEmpty()) {
                _uiState.update { it.copy(initialText = draft) }
            }
            if (!replyToId.isNullOrEmpty()) {
                val event = try {
                    resolveReplyTarget(replyToId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                _uiState.update {
                    it.copy(
                        replyToEvent = event,
                        replyResolutionPending = false,
                        error = if (event == null) "Couldn't load the reply target. This reply is blocked to prevent publishing it as a standalone note." else it.error,
                    )
                }
            }
            if (!quoteToId.isNullOrEmpty()) {
                val event = eventRepository.getById(quoteToId)
                if (event != null) {
                    val ref = "nostr:${Nip19.hexToNevent(event.id, event.pubkey, event.kind)}"
                    _uiState.update { it.copy(quoteToEvent = event, initialText = "\n\n$ref") }
                }
            }
            if (!quoteFolderId.isNullOrEmpty()) {
                val events = try {
                    bookmarksRepository.getBookmarks(quoteFolderId).map { it.event }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _uiState.update { it.copy(error = "Could not read this bookmark folder") }
                    return@launch
                }
                if (events.isNotEmpty()) {
                    val refs = events.joinToString("\n") { "nostr:${Nip19.hexToNevent(it.id, it.pubkey, it.kind)}" }
                    _uiState.update { it.copy(quoteFolderEvents = events, initialText = "\n\n$refs") }
                } else {
                    _uiState.update { it.copy(error = "This bookmark folder is empty") }
                }
            }
        }

        // Debounced @ mention search: local cache first, then the search relay.
        viewModelScope.launch {
            _mentionQuery.debounce(250).collectLatest { query ->
                if (query.isNullOrBlank()) {
                    currentMentionSubId?.let { pool.unsubscribe(it) }
                    currentMentionSubId = null
                    _mentionSuggestions.update { emptyList() }
                    _mentionSearching.update { false }
                } else {
                    searchMentions(query)
                }
            }
        }

        // When Beaver Mode is enabled, also surface the account's published
        // Blossom servers (kind 10063) in the Write-tab chooser so the user can
        // explicitly click any single server (local or published) to override
        // Settings rules for an upload.
        viewModelScope.launch {
            combine(appSettings.blossomBeaverMode, accountRepository.activeAccount) { beaverMode, account ->
                beaverMode to account
            }.collectLatest { (beaverMode, account) ->
                if (!beaverMode || account == null) {
                    publishedBlossomServers.value = emptyList()
                    return@collectLatest
                }
                val fetched = withContext(Dispatchers.IO) {
                    try {
                        val result = relayListFetcher.fetchLatest(
                            pubkey = account.pubkey,
                            kind = EventKind.BLOSSOM_SERVER_LIST,
                            seedRelays = account.effectiveRelays(),
                        )
                        result.event?.let { PublishedRelayLists.blossomServers(it) }.orEmpty()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                publishedBlossomServers.value = fetched
            }
        }

        // Load Blossom servers for the Write-tab chooser row after persisted
        // settings finish loading, so startup defaults never clobber a restored
        // or user-selected server override.
        viewModelScope.launch {
            appSettings.initialized.first { it }
            combine(
                appSettings.blossomServers,
                appSettings.blossomDefaultServer,
                appSettings.blossomBeaverMode,
                publishedBlossomServers,
            ) { localServers, defaultServer, beaverMode, published ->
                val merged = LinkedHashSet<String>()
                merged += localServers
                if (beaverMode) merged += published
                val autoLabel = when {
                    beaverMode -> "beaver"
                    defaultServer != null && defaultServer in localServers -> "default"
                    else -> "random"
                }
                merged.sorted() to autoLabel
            }.collect { (servers, autoLabel) ->
                _uiState.update { state ->
                    // Never silently discard an explicit Write-tab selection
                    // while DataStore or Beaver Mode servers are updating.
                    val selected = state.blossomServer
                    val offered = if (selected != null && selected !in servers) {
                        (servers + selected).sorted()
                    } else {
                        servers
                    }
                    state.copy(
                        blossomServers = offered,
                        blossomAutoLabel = autoLabel,
                    )
                }
            }
        }
    }

    /**
     * Resolve the explicitly requested reply target before enabling SEND.
     * A missing local cache entry must never turn a reply into an ordinary note.
     */
    private suspend fun resolveReplyTarget(id: String): Event? {
        val local = runCatching { eventRepository.getById(id) }.getOrNull()
        if (local != null && local.verify()) return local

        val relay = Tunables.SEARCH_RELAY
        pool.acquireRelay(relay)
        val subId = "compose-reply-" + java.util.UUID.randomUUID().toString().take(10)
        try {
            pool.subscribeTo(
                listOf(Filter(ids = listOf(id), limit = 1)),
                id = subId,
                label = "compose-reply-target",
                relays = setOf(relay),
            )
            val result = withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                pool.messages.first { (relayUrl, message) ->
                    RelayPool.normalize(relayUrl) == RelayPool.normalize(relay) && when (message) {
                        is RelayMessage.EventMessage -> message.subscriptionId == subId &&
                            message.event.id.equals(id, ignoreCase = true) && message.event.verify()
                        is RelayMessage.EndOfStoredEvents -> message.subscriptionId == subId
                        else -> false
                    }
                }
            }
            val event = (result?.message as? RelayMessage.EventMessage)?.event ?: return null
            runCatching { eventRepository.save(event, "") }
            return event
        } finally {
            pool.unsubscribe(subId)
            pool.releaseRelay(relay)
        }
    }

    // ── Blossom media uploads ────────────────────────────────────────────────

    fun setBlossomServer(url: String?) {
        val normalized = url?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        savedStateHandle[KEY_BLOSSOM_SERVER] = normalized
        _uiState.update { it.copy(blossomServer = normalized) }
    }

    fun setSelectedAudioPlaylist(folderId: String?) {
        val validId = folderId?.takeIf { id -> _uiState.value.audioPlaylists.any { it.id == id } }
        _uiState.update { it.copy(selectedAudioPlaylistId = validId) }
    }

    /** Queue one upload; multiple picker results are processed sequentially and all URLs kept. */
    fun attachMedia(uri: Uri, kind: String, preserveOriginalAudio: Boolean = false) {
        if (kind !in setOf("image", "video", "audio", "file")) return
        val selectedServer = _uiState.value.blossomServer
            ?: savedStateHandle.get<String>(KEY_BLOSSOM_SERVER)?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        synchronized(uploadQueueLock) {
            pendingUploads += 1
            _uiState.update { it.copy(isUploading = true) }
            val queued = mediaUploadQueue.trySend(
                MediaUploadRequest(uri, kind, selectedServer, preserveOriginalAudio && kind == "audio"),
            )
            if (queued.isFailure) {
                pendingUploads = (pendingUploads - 1).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isUploading = pendingUploads > 0,
                        uploadingKind = null,
                    )
                }
                uploadErrorQueue.trySend("upload queue is unavailable")
            }
        }
    }

    private suspend fun uploadMedia(request: MediaUploadRequest) {
        _uiState.update {
            it.copy(uploadingKind = request.kind)
        }
        var preparedFile: java.io.File? = null
        try {
            val signer = signerFactory.forActiveAccount()
                ?: error("no active account")
            val compress = appSettings.blossomCompress.value
            val prepared = when (request.kind) {
                "image" -> MediaProcessor.prepareImageMedia(context, request.uri, compress = compress)
                "video" -> MediaProcessor.prepareVideoMedia(context, request.uri, compress = compress)
                "audio" -> MediaProcessor.prepareAudioMedia(
                    context,
                    request.uri,
                    // Original-audio is an explicit override: copy source bytes
                    // exactly, preserving ID3/container metadata regardless of
                    // either compression preference.
                    compress = !request.preserveOriginalAudio &&
                        (compress || appSettings.blossomAudioCompress.value),
                )
                "file" -> MediaProcessor.prepareFileMedia(context, request.uri)
                else -> null
            } ?: error("could not read ${request.kind} media")
            preparedFile = prepared.file
            val label = when (request.kind) {
                "image" -> "Uploading image"
                "video" -> "Uploading video"
                "audio" -> "Uploading audio"
                else -> "Uploading file"
            }
            val url = blossomUploader.upload(
                file = prepared.file,
                mime = prepared.mime,
                preferredServer = request.preferredServer,
                signer = signer,
                alt = label,
                extension = prepared.extension,
            )
            val musicMetadata = if (request.kind == "audio") withContext(Dispatchers.IO) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(prepared.file.absolutePath)
                    listOf(
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE),
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    )
                } catch (_: Exception) { emptyList<String?>() }
                finally { retriever.release() }
            } else emptyList()
            if (request.kind == "image" || request.kind == "video" || request.kind == "audio") {
                val blurhash = if (request.kind == "image") {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            encodeBlurHashScaled(prepared.file.absolutePath)
                        }.getOrNull()
                    }
                } else null
                uploadedMediaHints[url] = UploadedMediaHint(
                    url = url,
                    mime = prepared.mime,
                    title = musicMetadata.getOrNull(0),
                    artist = musicMetadata.getOrNull(1),
                    album = musicMetadata.getOrNull(2),
                    width = prepared.width,
                    height = prepared.height,
                    blurhash = blurhash,
                )
            }
            if (request.kind == "audio") {
                _uiState.update { state ->
                    val uploadedUrls = (state.uploadedAudioUrls + url).distinct()
                    val originalUrls = if (request.preserveOriginalAudio) {
                        (state.originalAudioUrls + url).distinct()
                    } else state.originalAudioUrls
                    state.copy(
                        hasUploadedAudio = true,
                        uploadedAudioUrls = uploadedUrls,
                        originalAudioUrls = originalUrls,
                    )
                }
            }
            uploadedUrlQueue.trySend(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "upload failed"
            uploadErrorQueue.trySend(message)
        } finally {
            // Option A hygiene: staging file lives in cacheDir — remove after upload
            try { preparedFile?.delete() } catch (_: Exception) {}

            synchronized(uploadQueueLock) {
                pendingUploads = (pendingUploads - 1).coerceAtLeast(0)
                _uiState.update {
                    it.copy(
                        isUploading = pendingUploads > 0,
                        uploadingKind = null,
                    )
                }
            }
        }
    }

    fun setMentionQuery(q: String?) = _mentionQuery.update { q }

    private suspend fun searchMentions(query: String) {
        _mentionSearching.update { true }

        // Full local cache search first (RAM + Room up to 75k profiles) — instant, works offline.
        val local = profileRepository.searchCachedProfiles(query, limit = 15)
            .map { (pubkey, p) -> ProfileMatch(pubkey, p) }
            .sortedWith(mentionSort(query))
            .take(10)
        _mentionSuggestions.update { local }

        if (!mentionSearchRelayAdded) {
            pool.acquireRelay(Tunables.SEARCH_RELAY)
            mentionSearchRelayAdded = true
        }
        currentMentionSubId?.let { pool.unsubscribe(it) }
        val subId = pool.subscribe(listOf(
            Filter(kinds = listOf(EventKind.METADATA), search = query, limit = 15)
        ))
        currentMentionSubId = subId

        try {
            // EOSE timeout: stop even if the relay never sends EOSE.
            withTimeoutOrNull(Tunables.SEARCH_EOSE_TIMEOUT_MS) {
                pool.messages.collect { (_, msg) ->
                    when (msg) {
                        is RelayMessage.EventMessage -> {
                            if (msg.subscriptionId == subId && msg.event.verify()) {
                                profileRepository.processEvent(msg.event)
                                val pubkey = msg.event.pubkey
                                if (_mentionSuggestions.value.none { it.pubkey == pubkey }) {
                                    _mentionSuggestions.update {
                                        (it + ProfileMatch(pubkey, profileRepository.getProfile(pubkey)))
                                            .sortedWith(mentionSort(query))
                                            .take(10)
                                    }
                                }
                            }
                        }
                        is RelayMessage.EndOfStoredEvents -> {
                            if (msg.subscriptionId == subId) {
                                pool.unsubscribe(subId)
                                if (currentMentionSubId == subId) currentMentionSubId = null
                                currentCoroutineContext().cancel()
                            }
                        }
                        else -> Unit
                    }
                }
            }
        } finally {
            if (currentMentionSubId == subId) currentMentionSubId = null
            pool.unsubscribe(subId)
            _mentionSearching.update { false }
        }
    }

    private fun mentionSort(query: String) = compareBy<ProfileMatch>(
        { !(it.profile?.bestName?.startsWith(query, ignoreCase = true) == true) },
        { it.profile?.bestName },
    )

    override fun onCleared() {
        mediaUploadQueue.close()
        uploadedUrlQueue.close()
        uploadErrorQueue.close()
        publishEffectQueue.close()
        if (mentionSearchRelayAdded) {
            pool.releaseRelay(Tunables.SEARCH_RELAY)
            mentionSearchRelayAdded = false
        }
    }

    fun togglePoll() {
        val enabled = !_uiState.value.pollEnabled
        _uiState.update { it.copy(pollEnabled = enabled) }
        if (enabled) {
            _uiState.update { it.copy(pollOptions = listOf("", ""), pollMultiple = false) }
        }
    }

    fun setPollOption(index: Int, text: String) {
        _uiState.update { state ->
            val options = state.pollOptions.toMutableList()
            if (index in options.indices) options[index] = text
            state.copy(pollOptions = options)
        }
    }

    fun addPollOption() {
        _uiState.update { state ->
            if (state.pollOptions.size < Tunables.MAX_POLL_OPTIONS)
                state.copy(pollOptions = state.pollOptions + "")
            else state
        }
    }

    fun removePollOption(index: Int) {
        _uiState.update { state ->
            if (state.pollOptions.size > 2 && index in state.pollOptions.indices)
                state.copy(pollOptions = state.pollOptions.toMutableList().apply { removeAt(index) })
            else state
        }
    }

    fun togglePollType() {
        _uiState.update { it.copy(pollMultiple = !it.pollMultiple) }
    }

    /**
     * Signs and publishes exactly one note. Navigation is delivered as a
     * one-shot [ComposeEffect.Published] event; no UI callback is held by this
     * coroutine, and every signing/relay/database failure is surfaced in state
     * instead of escaping as an uncaught exception.
     */
    fun publish(
        content: String,
        tagNpubs: List<String> = emptyList(),
        tagHashtags: List<String> = emptyList(),
    ) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        val draftState = _uiState.value
        val originalAudioPost = draftState.originalAudioUrls.any { url -> trimmed.contains(url) }
        val hasAudioAttachment = draftState.uploadedAudioUrls.any { url -> trimmed.contains(url) }
        val selectedPlaylist = draftState.audioPlaylists.firstOrNull {
            it.id == draftState.selectedAudioPlaylistId
        }.takeIf { hasAudioAttachment }
        val bodyContent = if (originalAudioPost &&
            !Regex("(?i)#tbonemusic\\b").containsMatchIn(trimmed)
        ) "$trimmed\n\n#TboneMusic" else trimmed
        if (_uiState.value.replyTargetRequired && _uiState.value.replyToEvent == null) {
            _uiState.update {
                it.copy(error = "Reply target is unavailable. Publishing is blocked so this cannot become a standalone note.")
            }
            return
        }
        // A fast double-tap on SEND must not create two publish jobs before
        // isPublishing recomposes. This is independent of the Compose flag.
        if (!publishInFlight.compareAndSet(false, true)) return
        _uiState.update { it.copy(isPublishing = true, error = null) }

        viewModelScope.launch {
            try {
                val signer = signerFactory.forActiveAccount()
                if (signer == null) {
                    _uiState.update { it.copy(isPublishing = false, error = "No active account") }
                    return@launch
                }

                val replyTo = _uiState.value.replyToEvent
                val explicitQuote = _uiState.value.quoteToEvent
                // Quoting is explicit only: a reply to a note that itself
                // quotes another note must not silently quote that note too.
                // Only the chosen quote / quote-folder events get q-tags.
                val quoteEvents = (
                    _uiState.value.quoteFolderEvents + listOfNotNull(explicitQuote)
                ).distinctBy { it.id }
                val quoteIds = quoteEvents.map { it.id }

                val tags = buildList {
                    if (replyTo != null) addAll(nip10ReplyTags(replyTo))
                    // NIP-18 quote references remain separate from NIP-10 reply
                    // e-tags. Folder quotes emit one q-tag (and author p-tag) per
                    // saved event, preserving the folder's displayed order.
                    quoteIds.forEach { quoteId ->
                        add(buildJsonArray { add(JsonPrimitive("q")); add(JsonPrimitive(quoteId)) })
                        quoteEvents.firstOrNull { it.id == quoteId }?.let { quoted ->
                            add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(quoted.pubkey)) })
                        }
                    }
                    // User-added @mentions (npubs → p-tags) and #hashtags (t-tags).
                    tagNpubs.distinct().forEach { npub ->
                        val hex = Nip19.npubToHex(npub.trim().removePrefix("nostr:"))
                        if (hex != null) {
                            add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(hex)) })
                        }
                    }
                    (tagHashtags + if (originalAudioPost) listOf("TboneMusic") else emptyList())
                        .map { tag ->
                            tag.trim().removePrefix("#").lowercase()
                                .filter { it.isLetterOrDigit() || it in "_" }
                        }
                        .filter(String::isNotEmpty)
                        .distinct()
                        .forEach { clean ->
                            add(buildJsonArray { add(JsonPrimitive("t")); add(JsonPrimitive(clean)) })
                        }
                }

                // NIP-92 display hints for media uploaded in this session,
                // so other clients (and this one) can size frames and show
                // blurred previews before the media downloads.
                val mediaTags = uploadedMediaHints.values
                    .filter { hint -> bodyContent.contains(hint.url) }
                    .sortedBy { hint -> bodyContent.indexOf(hint.url) }
                    .map { hint -> buildImetaTag(hint) }
                val tagsWithMedia = tags + mediaTags

                // Keep every quoted event visible to NIP-27 clients. Links are
                // grouped below the writing line, in the same order as the folder.
                val missingRefs = quoteIds
                    .map { id ->
                        val quoted = quoteEvents.firstOrNull { it.id == id }
                        val reference = if (quoted != null) {
                            Nip19.hexToNevent(id, quoted.pubkey, quoted.kind)
                        } else {
                            Nip19.hexToNevent(id)
                        }
                        "nostr:$reference"
                    }
                    .filterNot { bodyContent.contains(it) }
                val finalContent = if (missingRefs.isNotEmpty()) {
                    "$bodyContent\n\n${missingRefs.joinToString("\n")}"
                } else {
                    bodyContent
                }

                val isPoll = _uiState.value.pollEnabled
                val pollKindTags = if (isPoll) {
                    val nonBlank = _uiState.value.pollOptions.map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .take(Tunables.MAX_POLL_OPTIONS)
                    if (nonBlank.size < 2) {
                        _uiState.update { it.copy(isPublishing = false, error = "Poll needs at least 2 options") }
                        return@launch
                    }
                    Nip88.buildPollTags(
                        options = nonBlank.map { label -> Nip88.PollOption(Nip88.generateOptionId(), label) },
                        pollType = if (_uiState.value.pollMultiple) Nip88.PollType.MULTIPLECHOICE else Nip88.PollType.SINGLECHOICE,
                    )
                } else null

                val unsigned = UnsignedEvent(
                    pubkey = signer.pubkey,
                    kind = if (isPoll) EventKind.POLL else EventKind.TEXT_NOTE,
                    content = finalContent,
                    tags = if (pollKindTags != null) {
                        tagsWithMedia + pollKindTags.map { tag ->
                            kotlinx.serialization.json.buildJsonArray {
                                tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            }
                        }
                    } else tagsWithMedia,
                )

                // getOrElse keeps signer failures in the error state. The outer
                // catch also covers relays/Room/encoding failures that used to
                // escape from Result.onSuccess and kill the app.
                val event = signer.signEvent(unsigned).getOrElse { signError ->
                    _uiState.update {
                        it.copy(
                            isPublishing = false,
                            error = signError.message?.takeIf { it.isNotBlank() } ?: "Couldn't sign note",
                        )
                    }
                    return@launch
                }
                pool.publish(event)
                eventRepository.save(event, signer.pubkey)
                selectedPlaylist?.let { playlist ->
                    val result = runCatching {
                        if (playlist.isOnline) bookmarkSyncRepository.addToOnlineFolder(playlist, event.id)
                        else { bookmarksRepository.addBookmark(event, playlist.id); null }
                    }.getOrNull()
                    if (playlist.isOnline && result?.ok != true) {
                        uploadErrorQueue.trySend(
                            "published the song, but couldn't add it to ${playlist.name}: " +
                                (result?.message ?: "playlist update failed"),
                        )
                    }
                }
                _uiState.update { it.copy(isPublishing = false) }
                publishEffectQueue.trySend(ComposeEffect.Published)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        isPublishing = false,
                        error = error.message?.take(240)?.takeIf { it.isNotBlank() }
                            ?: "Couldn't publish note",
                    )
                }
            } finally {
                publishInFlight.set(false)
            }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}


/**
 * Display hints for one uploaded medium (NIP-92): the MIME type, pixel
 * dimensions measured from the prepared file, and for images a BlurHash
 * preview computed at upload time.
 */
internal data class UploadedMediaHint(
    val url: String,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val blurhash: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/** The `imeta` tag entries for [hint], in tag order (bare values, no "imeta" head). */
internal fun imetaTagEntries(hint: UploadedMediaHint): List<String> = buildList {
    add("url ${hint.url}")
    add("m ${hint.mime}")
    hint.title?.takeIf { it.isNotBlank() }?.let { add("title $it") }
    hint.artist?.takeIf { it.isNotBlank() }?.let { add("artist $it") }
    hint.album?.takeIf { it.isNotBlank() }?.let { add("album $it") }
    if (hint.width != null && hint.height != null && hint.width > 0 && hint.height > 0) {
        add("dim ${hint.width}x${hint.height}")
    }
    if (!hint.blurhash.isNullOrBlank()) {
        add("blurhash ${hint.blurhash}")
    }
}

/** Builds the full `["imeta", "url …", "m …", …]` tag for [hint]. */
internal fun buildImetaTag(hint: UploadedMediaHint): kotlinx.serialization.json.JsonArray =
    kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive("imeta"))
        imetaTagEntries(hint).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }

/**
 * Computes a power-of-two [android.graphics.BitmapFactory.Options.inSampleSize]
 * so the decoded preview stays small (~128px) instead of allocating a 50-200 MB
 * full-resolution camera bitmap just to compute a 32x32 BlurHash.
 */
internal fun computeBlurHashSampleSize(width: Int, height: Int, targetMaxDim: Int = 128): Int {
    if (width <= 0 || height <= 0 || targetMaxDim <= 0) return 1
    var sample = 1
    while ((width / (sample * 2)) >= targetMaxDim || (height / (sample * 2)) >= targetMaxDim) {
        sample *= 2
    }
    return sample
}

private fun encodeBlurHashScaled(filePath: String): String? {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(filePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decodeOpts = android.graphics.BitmapFactory.Options().apply {
        inSampleSize = computeBlurHashSampleSize(bounds.outWidth, bounds.outHeight, targetMaxDim = 128)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bmp = android.graphics.BitmapFactory.decodeFile(filePath, decodeOpts) ?: return null
    return try {
        social.tbone.util.BlurHash.encodeBitmap(bmp)
    } finally {
        if (!bmp.isRecycled) bmp.recycle()
    }
}
