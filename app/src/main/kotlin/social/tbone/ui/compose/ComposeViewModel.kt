package social.tbone.ui.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.Tunables
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
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
import social.tbone.nostr.replyToPubkeys
import social.tbone.notes.BookmarksRepository
import social.tbone.nostr.rootEventId
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.ui.search.ProfileMatch
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

data class ComposeUiState(
    val isPublishing: Boolean = false,
    val error: String? = null,
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
    /** Server chosen for the next upload (null = default/random from pool). */
    val blossomServer: String? = null,
    /** Enabled Blossom servers (defaults + custom). */
    val blossomServers: List<String> = emptyList(),
)

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
    private val blossomUploader: BlossomUploader,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val replyToId: String? = savedStateHandle["replyToId"]
    private val quoteToId: String? = savedStateHandle["quoteToId"]
    private val quoteFolderId: String? = savedStateHandle["quoteFolderId"]
    private val draft: String? = savedStateHandle["draft"]
    private val audioPath: String? = savedStateHandle["audioPath"]

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    // ── Active account (avatar switch + publish) ─────────────────────────────
    val accounts: StateFlow<List<Account>> = accountRepository.accounts

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount

    val profiles: StateFlow<Map<String, social.tbone.nostr.ProfileContent>> = profileRepository.profiles

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    // Preview content builder — mirrors publish() finalContent logic without side effects.
    fun buildPreviewContent(trimmed: String): String {
        val explicitQuote = _uiState.value.quoteToEvent
        val inheritedQuoteId = _uiState.value.replyToEvent?.parsedTags?.quotedEventId
        val quoteIds = (
            _uiState.value.quoteFolderEvents.map { it.id } +
            listOfNotNull(explicitQuote?.id, inheritedQuoteId)
            ).distinct()
        val missingRefs = quoteIds
            .map { id ->
                val quoted = _uiState.value.quoteFolderEvents.firstOrNull { it.id == id } ?: explicitQuote
                val reference = if (quoted != null) Nip19.hexToNevent(id, quoted.pubkey, quoted.kind) else Nip19.hexToNevent(id)
                "nostr:$reference"
            }
            .filterNot { trimmed.contains(it) }
        return if (missingRefs.isNotEmpty()) "$trimmed\n\n${missingRefs.joinToString("\n")}" else trimmed
    }

    private data class MediaUploadRequest(val uri: Uri, val kind: String)

    private val mediaUploadQueue = Channel<MediaUploadRequest>(Channel.UNLIMITED)
    private val uploadedUrlQueue = Channel<String>(Channel.UNLIMITED)
    private val uploadErrorQueue = Channel<String>(Channel.UNLIMITED)
    val uploadedMediaUrls = uploadedUrlQueue.receiveAsFlow()
    val uploadErrors = uploadErrorQueue.receiveAsFlow()
    private val uploadQueueLock = Any()
    private var pendingUploads = 0

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
                val event = eventRepository.getById(replyToId)
                _uiState.update { it.copy(replyToEvent = event) }
            }
            if (!quoteToId.isNullOrEmpty()) {
                val event = eventRepository.getById(quoteToId)
                if (event != null) {
                    val ref = "nostr:${Nip19.hexToNevent(event.id, event.pubkey, event.kind)}"
                    _uiState.update { it.copy(quoteToEvent = event, initialText = "\n\n$ref") }
                }
            }
            if (!quoteFolderId.isNullOrEmpty()) {
                val events = bookmarksRepository.getBookmarks(quoteFolderId).map { it.event }
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

        // Load the enabled blossom server pool for the chooser row.
        viewModelScope.launch {
            appSettings.blossomServers.collect { servers ->
                _uiState.update { it.copy(blossomServers = servers.sorted()) }
            }
        }
    }

    // ── Blossom media uploads ────────────────────────────────────────────────

    fun setBlossomServer(url: String?) = _uiState.update { it.copy(blossomServer = url) }

    /** Queue one upload; multiple picker results are processed sequentially and all URLs kept. */
    fun attachMedia(uri: Uri, kind: String) {
        if (kind !in setOf("image", "video", "audio")) return
        synchronized(uploadQueueLock) {
            pendingUploads += 1
            _uiState.update { it.copy(isUploading = true) }
            val queued = mediaUploadQueue.trySend(MediaUploadRequest(uri, kind))
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
                    compress = appSettings.blossomAudioCompress.value,
                )
                else -> null
            } ?: error("could not read ${request.kind} media")
            preparedFile = prepared.file
            val label = when (request.kind) {
                "image" -> "Uploading image"
                "video" -> "Uploading video"
                else -> "Uploading audio"
            }
            val url = blossomUploader.upload(
                file = prepared.file,
                mime = prepared.mime,
                preferredServer = _uiState.value.blossomServer,
                signer = signer,
                alt = label,
                extension = prepared.extension,
            )
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

        // Local cache matches first — instant, works offline.
        val local = profileRepository.profiles.value
            .filter { (_, p) ->
                val name = p.bestName ?: p.name ?: ""
                name.contains(query, ignoreCase = true) ||
                    p.nip05?.contains(query, ignoreCase = true) == true
            }
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

    fun publish(
        content: String,
        onSuccess: () -> Unit,
        tagNpubs: List<String> = emptyList(),
        tagHashtags: List<String> = emptyList(),
    ) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isPublishing = true, error = null) }

            val signer = signerFactory.forActiveAccount()
            if (signer == null) {
                _uiState.update { it.copy(isPublishing = false, error = "No active account") }
                return@launch
            }

            val replyTo = _uiState.value.replyToEvent
            val explicitQuote = _uiState.value.quoteToEvent
            // A reply opened from a quote-note still carries the quote's
            // relationship. This prevents the composer from silently dropping
            // the q reference while it correctly adds the new reply target.
            val inheritedQuoteId = replyTo?.parsedTags?.quotedEventId
            val inheritedQuote = if (explicitQuote == null && inheritedQuoteId != null) {
                eventRepository.getById(inheritedQuoteId)
            } else null
            val quoteEvents = (
                _uiState.value.quoteFolderEvents + listOfNotNull(explicitQuote, inheritedQuote)
            ).distinctBy { it.id }
            val quoteIds = (quoteEvents.map { it.id } + listOfNotNull(inheritedQuoteId)).distinct()

            val tags = buildList {
                if (replyTo != null) {
                    // NIP-10: retain the root and mark the actual parent as
                    // reply. Do not let a q-tag or a mention become the reply
                    // target, and do not emit the same event twice when replying
                    // directly to a root note.
                    val rootId = replyTo.parsedTags.rootEventId
                    if (rootId != null && rootId != replyTo.id) {
                        add(buildJsonArray {
                            add(JsonPrimitive("e")); add(JsonPrimitive(rootId))
                            add(JsonPrimitive("")); add(JsonPrimitive("root"))
                        })
                    }
                    add(buildJsonArray {
                        add(JsonPrimitive("e")); add(JsonPrimitive(replyTo.id))
                        add(JsonPrimitive("")); add(JsonPrimitive("reply"))
                    })

                    // p tags: author of the actual reply target + existing
                    // NIP-10 participants. The quote's author is added below
                    // separately, so both relationships survive publishing.
                    val mentions = (listOf(replyTo.pubkey) + replyTo.parsedTags.replyToPubkeys).distinct()
                    mentions.forEach { pubkey ->
                        add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(pubkey)) })
                    }
                }
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
                tagHashtags.distinct().forEach { tag ->
                    val clean = tag.trim().removePrefix("#").lowercase()
                        .filter { it.isLetterOrDigit() || it in "_" }
                    if (clean.isNotEmpty()) {
                        add(buildJsonArray { add(JsonPrimitive("t")); add(JsonPrimitive(clean)) })
                    }
                }
            }

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
                .filterNot { trimmed.contains(it) }
            val finalContent = if (missingRefs.isNotEmpty()) {
                "$trimmed\n\n${missingRefs.joinToString("\n")}"
            } else {
                trimmed
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
                    tags + pollKindTags.map { tag ->
                        kotlinx.serialization.json.buildJsonArray {
                            tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        }
                    }
                } else tags,
            )

            signer.signEvent(unsigned)
                .onSuccess { event ->
                    pool.publish(event)
                    eventRepository.save(event, signer.pubkey)
                    _uiState.update { it.copy(isPublishing = false) }
                    onSuccess()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isPublishing = false, error = e.message) }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
