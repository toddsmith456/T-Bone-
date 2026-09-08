package social.tbone.ui.compose

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.Tunables
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
import social.tbone.media.BlossomUploader
import social.tbone.media.MediaProcessor
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip88
import social.tbone.nostr.Nip19
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.replyToPubkeys
import social.tbone.nostr.rootEventId
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.ui.search.ProfileMatch
import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

data class ComposeUiState(
    val isPublishing: Boolean = false,
    val error: String? = null,
    val replyToEvent: Event? = null,
    val quoteToEvent: Event? = null,
    val initialText: String = "",
    // NIP-88 poll builder
    val pollEnabled: Boolean = false,
    val pollOptions: List<String> = listOf("", ""),
    val pollMultiple: Boolean = false,
    // Blossom media uploads
    val isUploading: Boolean = false,
    val uploadError: String? = null,
    /** Server chosen for the next upload (null = default/random from pool). */
    val blossomServer: String? = null,
    /** Enabled Blossom servers (defaults + custom). */
    val blossomServers: List<String> = emptyList(),
    /** Set once an upload finishes; the screen consumes it and clears it. */
    val lastUploadUrl: String? = null,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val eventRepository: EventRepository,
    private val profileRepository: ProfileRepository,
    private val appSettings: AppSettings,
    private val blossomUploader: BlossomUploader,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val replyToId: String? = savedStateHandle["replyToId"]
    private val quoteToId: String? = savedStateHandle["quoteToId"]
    private val draft: String? = savedStateHandle["draft"]

    private val _uiState = MutableStateFlow(ComposeUiState())
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

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
                    val ref = "nostr:${Nip19.hexToNote(event.id)}"
                    _uiState.update { it.copy(quoteToEvent = event, initialText = ref) }
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

    /** Uploads [uri] to Blossom and exposes the resulting URL via lastUploadUrl. */
    fun attachMedia(uri: Uri, kind: String, mime: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUploading = true, uploadError = null) }
            try {
                val signer = signerFactory.forActiveAccount()
                    ?: error("no active account")
                val compress = appSettings.blossomCompress.value
                val prepared = if (kind == "image") {
                    MediaProcessor.prepareImage(context, uri, compress = compress)
                } else {
                    MediaProcessor.prepareVideo(context, uri, compress = compress)
                } ?: error("could not read media")
                val url = blossomUploader.upload(
                    file = prepared,
                    mime = mime,
                    preferredServer = _uiState.value.blossomServer,
                    signer = signer,
                )
                _uiState.update { it.copy(isUploading = false, lastUploadUrl = url) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isUploading = false, uploadError = e.message ?: "upload failed")
                }
            }
        }
    }

    /** Called by the screen after it has inserted the uploaded URL into the text. */
    fun consumeUploadedUrl() = _uiState.update { it.copy(lastUploadUrl = null) }

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
            if (state.pollOptions.size < 10)
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
            val quoteTo = _uiState.value.quoteToEvent

            val tags = buildList {
                if (replyTo != null) {
                    // NIP-10: root + reply e-tags with markers
                    val rootId = replyTo.parsedTags.rootEventId
                    if (rootId != null) {
                        add(buildJsonArray {
                            add(JsonPrimitive("e")); add(JsonPrimitive(rootId))
                            add(JsonPrimitive("")); add(JsonPrimitive("root"))
                        })
                        add(buildJsonArray {
                            add(JsonPrimitive("e")); add(JsonPrimitive(replyTo.id))
                            add(JsonPrimitive("")); add(JsonPrimitive("reply"))
                        })
                    } else {
                        add(buildJsonArray {
                            add(JsonPrimitive("e")); add(JsonPrimitive(replyTo.id))
                            add(JsonPrimitive("")); add(JsonPrimitive("reply"))
                        })
                    }
                    // p tags: author of the note + existing p-tagged participants
                    val mentions = (listOf(replyTo.pubkey) + replyTo.parsedTags.replyToPubkeys).distinct()
                    mentions.forEach { pubkey ->
                        add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(pubkey)) })
                    }
                }
                if (quoteTo != null) {
                    add(buildJsonArray { add(JsonPrimitive("q")); add(JsonPrimitive(quoteTo.id)) })
                    add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(quoteTo.pubkey)) })
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

            // For quote-reply, ensure nostr:note1… ref is in the content
            val noteRef = quoteTo?.let { "\n\nnostr:${Nip19.hexToNote(it.id)}" }
            val finalContent = if (noteRef != null && !trimmed.contains(noteRef.trim()))
                "$trimmed$noteRef"
            else
                trimmed

            val isPoll = _uiState.value.pollEnabled
            val pollKindTags = if (isPoll) {
                val nonBlank = _uiState.value.pollOptions.map { it.trim() }.filter { it.isNotEmpty() }
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
