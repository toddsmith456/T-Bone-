package social.tbone.ui.toolbox.webxdc

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import social.tbone.webxdc.SavedXdcApp
import social.tbone.webxdc.XdcCustomTool
import social.tbone.webxdc.XdcFeedApp
import social.tbone.webxdc.XdcNipDc
import social.tbone.webxdc.XdcRepository
import social.tbone.webxdc.XdcStoreCatalog
import social.tbone.webxdc.XdcStoreEntry
import timber.log.Timber
import javax.inject.Inject

enum class XdcTab { SAVED, STORE, TBONE, NOSTR }

data class XdcUiState(
    val tab: XdcTab = XdcTab.SAVED,
    val query: String = "",
    val storeApps: List<XdcStoreEntry> = emptyList(),
    val storeLoading: Boolean = false,
    val storeError: String? = null,
    val tboneApps: List<XdcFeedApp> = emptyList(),
    val tboneLoading: Boolean = false,
    val tboneLoaded: Boolean = false,
    val nostrApps: List<XdcFeedApp> = emptyList(),
    val nostrLoading: Boolean = false,
    val nostrLoaded: Boolean = false,
    val message: String? = null,
) {
    fun filteredStore(): List<XdcStoreEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return storeApps
        return storeApps.filter {
            it.name.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.category.lowercase().contains(q)
        }
    }

    fun filteredFeed(apps: List<XdcFeedApp>): List<XdcFeedApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return apps
        return apps.filter {
            it.name.lowercase().contains(q) || (it.description?.lowercase()?.contains(q) == true)
        }
    }
}

/**
 * Drives the webxdc tool: the four ways to obtain a mini app
 * (local file, webxdc.org store, the T-Bone feed, the wider Nostr feed),
 * the saved library, and the "add as tool" promotion.
 *
 * Both Nostr feeds use NIP-DC's relay-indexable form — kind 1063 with
 * `#m = application/x-webxdc` — and differ only in the author filter.
 */
@HiltViewModel
class XdcViewModel @Inject constructor(
    private val repository: XdcRepository,
    private val pool: RelayPool,
    private val appSettings: AppSettings,
) : ViewModel() {

    companion object {
        /** npub1zx2tlzz35z44lcs47g3mgjts769wmxdjyzugc7jpfha7xrm72puq09ftjq */
        const val TBONE_XDC_PUBKEY = "1194bf8851a0ab5fe215f223b44970f68aed99b220b88c7a414dfbe30f7e5078"
        private const val FEED_LIMIT = 100

        /**
         * Ditto's relay is where the webxdc ecosystem publishes today; primal
         * and nos.lol carried both T-Bone's profile and webxdc events when this
         * was written. They are acquired for the lifetime of the tool so the
         * feeds work even if the user opens the toolbox before any feed.
         */
        private val XDC_RELAYS = listOf(
            "wss://relay.ditto.pub",
            "wss://relay.primal.net",
            "wss://nos.lol",
        )
    }

    private val _state = MutableStateFlow(XdcUiState())
    val state: StateFlow<XdcUiState> = _state.asStateFlow()

    val savedApps: StateFlow<List<SavedXdcApp>> = repository.savedApps
    val busyKeys: StateFlow<Set<String>> = repository.busy
    val customTools: StateFlow<List<XdcCustomTool>> = appSettings.xdcCustomTools

    private var tboneSubId: String? = null
    private var nostrSubId: String? = null
    private val tboneSeen = LinkedHashSet<String>()
    private val nostrSeen = LinkedHashSet<String>()

    init {
        XDC_RELAYS.forEach { pool.acquireRelay(it) }
        viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { (_, message) ->
                when (message) {
                    is RelayMessage.EventMessage -> handleEvent(message.subscriptionId, message.event)
                    is RelayMessage.EndOfStoredEvents -> handleEose(message.subscriptionId)
                    else -> Unit
                }
            }
        }
    }

    // ── Tabs / feeds ──────────────────────────────────────────────────────────

    fun selectTab(tab: XdcTab) {
        _state.update { it.copy(tab = tab) }
        when (tab) {
            XdcTab.STORE -> loadStore()
            XdcTab.TBONE -> ensureTboneFeed()
            XdcTab.NOSTR -> ensureNostrFeed()
            XdcTab.SAVED -> Unit
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun loadStore(force: Boolean = false) {
        if (_state.value.storeLoading) return
        if (!force && _state.value.storeApps.isNotEmpty()) return
        _state.update { it.copy(storeLoading = true, storeError = null) }
        viewModelScope.launch {
            runCatching { repository.fetchStoreCatalog() }
                .onSuccess { apps ->
                    _state.update { it.copy(storeApps = apps, storeLoading = false) }
                }
                .onFailure { error ->
                    Timber.w(error, "webxdc store fetch failed")
                    _state.update {
                        it.copy(
                            storeLoading = false,
                            storeError = error.message ?: "could not reach the store",
                        )
                    }
                }
        }
    }

    private fun ensureTboneFeed() {
        val current = _state.value
        if (current.tboneLoading || current.tboneLoaded) return
        _state.update { it.copy(tboneLoading = true) }
        tboneSubId = pool.subscribe(
            listOf(
                Filter(
                    kinds = listOf(EventKind.FILE_METADATA),
                    mTags = listOf(XdcNipDc.MIME),
                    authors = listOf(TBONE_XDC_PUBKEY),
                    limit = FEED_LIMIT,
                ),
            ),
            label = "xdc-tbone",
        )
        settleAfterTimeout(XdcTab.TBONE)
    }

    private fun ensureNostrFeed() {
        val current = _state.value
        if (current.nostrLoading || current.nostrLoaded) return
        _state.update { it.copy(nostrLoading = true) }
        nostrSubId = pool.subscribe(
            listOf(
                Filter(
                    kinds = listOf(EventKind.FILE_METADATA),
                    mTags = listOf(XdcNipDc.MIME),
                    limit = FEED_LIMIT,
                ),
            ),
            label = "xdc-nostr",
        )
        settleAfterTimeout(XdcTab.NOSTR)
    }

    /** Relays that ignore `#m` (or are simply offline) must not spin forever. */
    private fun settleAfterTimeout(tab: XdcTab) {
        viewModelScope.launch {
            delay(15_000)
            when (tab) {
                XdcTab.TBONE -> if (_state.value.tboneLoading) {
                    _state.update { it.copy(tboneLoading = false, tboneLoaded = true) }
                }
                XdcTab.NOSTR -> if (_state.value.nostrLoading) {
                    _state.update { it.copy(nostrLoading = false, nostrLoaded = true) }
                }
                else -> Unit
            }
        }
    }

    fun refreshFeed(tab: XdcTab) {
        when (tab) {
            XdcTab.TBONE -> {
                tboneSubId?.let { pool.unsubscribe(it) }
                tboneSubId = null
                tboneSeen.clear()
                _state.update { it.copy(tboneApps = emptyList(), tboneLoaded = false, tboneLoading = false) }
                ensureTboneFeed()
            }
            XdcTab.NOSTR -> {
                nostrSubId?.let { pool.unsubscribe(it) }
                nostrSubId = null
                nostrSeen.clear()
                _state.update { it.copy(nostrApps = emptyList(), nostrLoaded = false, nostrLoading = false) }
                ensureNostrFeed()
            }
            else -> Unit
        }
    }

    private fun handleEvent(subscriptionId: String, event: social.tbone.nostr.Event) {
        val isTbone = subscriptionId == tboneSubId
        val isNostr = subscriptionId == nostrSubId
        if (!isTbone && !isNostr) return
        if (!event.verify()) return
        val app = XdcNipDc.parseFileMetadata(
            kind = event.kind,
            eventId = event.id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            content = event.content,
            tags = event.tags.map { tag -> tag.mapNotNull { (it as? JsonPrimitive)?.content } },
        ) ?: return

        if (isTbone) {
            if (!tboneSeen.add(app.eventId)) return
            _state.update { it.copy(tboneApps = (it.tboneApps + app).sortedByDescending { a -> a.createdAt }) }
        } else {
            if (!nostrSeen.add(app.eventId)) return
            _state.update { it.copy(nostrApps = (it.nostrApps + app).sortedByDescending { a -> a.createdAt }) }
        }
    }

    private fun handleEose(subscriptionId: String) {
        when (subscriptionId) {
            tboneSubId -> _state.update { it.copy(tboneLoading = false, tboneLoaded = true) }
            nostrSubId -> _state.update { it.copy(nostrLoading = false, nostrLoaded = true) }
        }
    }

    // ── Saving ────────────────────────────────────────────────────────────────

    fun importLocal(uri: Uri) {
        viewModelScope.launch {
            runCatching { repository.importFromUri(uri) }
                .onSuccess { _state.update { s -> s.copy(message = "saved \"${it.name}\"") } }
                .onFailure { _state.update { s -> s.copy(message = it.message ?: "could not import that file") } }
        }
    }

    fun saveStoreEntry(entry: XdcStoreEntry) {
        val url = XdcStoreCatalog.downloadUrl(entry) ?: return
        viewModelScope.launch {
            runCatching {
                repository.downloadAndSave(url, entry.name, origin = "store", key = entry.appId)
            }.onSuccess {
                _state.update { s -> s.copy(message = "saved \"${it.name}\"") }
            }.onFailure {
                _state.update { s -> s.copy(message = it.message ?: "download failed") }
            }
        }
    }

    fun saveFeedApp(app: XdcFeedApp, origin: String) {
        viewModelScope.launch {
            runCatching {
                repository.downloadAndSave(app.url, app.name, origin = origin, uuid = app.uuid, key = app.eventId)
            }.onSuccess {
                _state.update { s -> s.copy(message = "saved \"${it.name}\"") }
            }.onFailure {
                _state.update { s -> s.copy(message = it.message ?: "download failed") }
            }
        }
    }

    fun deleteApp(app: SavedXdcApp) {
        viewModelScope.launch {
            runCatching { repository.delete(app.id) }
                .onSuccess { _state.update { s -> s.copy(message = "removed \"${app.name}\"") } }
                .onFailure { _state.update { s -> s.copy(message = it.message ?: "could not remove app") } }
        }
    }

    // ── Add as tool ───────────────────────────────────────────────────────────

    fun addAsTool(app: SavedXdcApp, name: String, iconId: String) {
        viewModelScope.launch {
            val tools = appSettings.xdcCustomTools.value.filterNot { it.appId == app.id }
            appSettings.setXdcCustomTools(
                tools + XdcCustomTool(
                    appId = app.id,
                    name = name.trim().ifEmpty { app.name }.take(40),
                    iconId = iconId,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            _state.update { s -> s.copy(message = "\"${name.ifBlank { app.name }}\" added to the toolbox") }
        }
    }

    fun removeTool(appId: String) {
        viewModelScope.launch {
            appSettings.setXdcCustomTools(appSettings.xdcCustomTools.value.filterNot { it.appId == appId })
            _state.update { s -> s.copy(message = "removed from the toolbox") }
        }
    }

    fun toolFor(appId: String): XdcCustomTool? = appSettings.xdcCustomTools.value.firstOrNull { it.appId == appId }

    fun iconFile(app: SavedXdcApp): java.io.File? = repository.iconFile(app)

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        tboneSubId?.let { pool.unsubscribe(it) }
        nostrSubId?.let { pool.unsubscribe(it) }
        XDC_RELAYS.forEach { pool.releaseRelay(it) }
        super.onCleared()
    }
}
