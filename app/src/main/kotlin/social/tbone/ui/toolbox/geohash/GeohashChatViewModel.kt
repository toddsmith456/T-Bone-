package social.tbone.ui.toolbox.geohash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import social.tbone.account.AccountRepository
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.geohash.GeohashKeyDerivation
import social.tbone.nostr.geohash.GeohashRelays
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.db.GeohashMessageDao
import social.tbone.settings.AppSettings
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** A message in a geohash channel. */
data class GeohashMessage(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val content: String,
    val nickname: String? = null,
    val own: Boolean = false,
)

/** How many newest messages the chat keeps (no older history is loaded). */
private const val MAX_MESSAGES = 40

/**
 * One geohash channel's chat. Relays are only connected when this screen is
 * open ([join]); leaving unsubscribes. Shows the 40 newest messages — the
 * limit is also applied on the relay filter so no older history is fetched.
 * Own messages appear immediately (optimistic add) so you always see what you
 * publish.
 */
@HiltViewModel
class GeohashChatViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val appSettings: AppSettings,
    private val pinnedMonitor: GeohashPinnedMonitor,
    private val geohashDao: GeohashMessageDao,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<GeohashMessage>>(emptyList())
    val messages: StateFlow<List<GeohashMessage>> = _messages.asStateFlow()

    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True once the channel's relays have answered (EOSE) — the feed is live. */
    private val _live = MutableStateFlow(false)
    val live: StateFlow<Boolean> = _live.asStateFlow()

    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var subId: String? = null
    private var collectJob: Job? = null
    private var currentGeohash: String = ""
    /** Relays this chat added to the pool — removed again on leave. */
    private val addedRelays = ConcurrentHashMap.newKeySet<String>()

    init {
        viewModelScope.launch {
            appSettings.geohashNickname.collect { _nickname.value = it }
        }
    }

    /** Connects to the group relays and subscribes to this channel. */
    fun join(code: String) {
        val g = code.lowercase().filter { it in "0123456789bcdefghjkmnpqrstuvwxyz" }
        if (g.isEmpty()) return
        leave()
        currentGeohash = g
        pinnedMonitor.markSeen(g) // clear the home badge when opening the chat
        seen.clear()
        _messages.value = emptyList()
        // Load cached messages from last 24h
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            val cached = geohashDao.getForChannel(g, cutoff)
            val loaded = cached.map {
                GeohashMessage(
                    id = it.id,
                    pubkey = it.pubkey,
                    createdAt = it.createdAt,
                    content = it.content,
                    nickname = it.nickname,
                    own = it.own,
                )
            }
            _messages.value = loaded.sortedBy { it.createdAt }.takeLast(MAX_MESSAGES)
            loaded.forEach { seen.add(it.id) }
            // Clean old cache
            geohashDao.deleteOlderThan(cutoff - 24L * 60L * 60L * 1000L)
        }
        _error.value = null
        _live.value = false

        viewModelScope.launch {
            // Only this channel's ~5 relays — not the account's whole list.
            val relays = GeohashRelays.closestRelays(g).toSet()
            relays.forEach {
                if (it !in pool.relayUrls()) {
                    pool.addRelay(it)
                    addedRelays.add(it)
                }
            }
            // Subscribe to the code AND its parent prefixes (4..len) so messages
            // posted at any coarser precision — city / province — arrive too.
            // That's exactly how Bitchat/Amethyst tag their messages.
            subId = pool.subscribeTo(
                listOf(
                    Filter(
                        kinds = listOf(20000),
                        gTags = GeohashRelays.watchGeohashes(g),
                        limit = MAX_MESSAGES,
                    )
                ),
                label = "geohash",
                relays = relays,
            )
        }
        collectJob = viewModelScope.launch(Dispatchers.Default) {
            pool.messages.collect { poolMessage ->
                val msg = poolMessage.message
                when (msg) {
                    is RelayMessage.EndOfStoredEvents -> {
                        if (msg.subscriptionId == subId) _live.value = true
                    }
                    is RelayMessage.EventMessage -> {
                        if (msg.subscriptionId != subId) return@collect
                        val event = msg.event
                        if (!event.verify()) return@collect
                        if (event.kind != 20000) return@collect
                        val gh = event.parsedTags.firstOrNull { it.name == "g" }?.value() ?: return@collect
                        // The relay already matched one of our watch geohashes;
                        // accept anything that's a prefix of (or equal to) the
                        // channel code — i.e. posted in this cell or a parent.
                        if (!currentGeohash.startsWith(gh)) return@collect
                        if (!seen.add(event.id)) return@collect
                        val n = event.parsedTags.firstOrNull { it.name == "n" }?.value()
                        appendMessage(
                            GeohashMessage(event.id, event.pubkey, event.createdAt, event.content, n, own = false),
                        )
                    }
                    else -> Unit
                }
            }
        }
    }

    fun leave() {
        collectJob?.cancel()
        collectJob = null
        subId?.let { pool.unsubscribe(it) }
        subId = null
        currentGeohash = ""
        _live.value = false
        // Drop the relays this chat connected (other channels re-add theirs).
        addedRelays.forEach { pool.removeRelay(it) }
        addedRelays.clear()
    }

    /** Posts a message; it appears immediately in the list (own = true). */
    fun send(text: String) {
        val g = currentGeohash
        if (g.isEmpty() || text.isBlank()) return
        viewModelScope.launch {
            val seed = appSettings.geohashIdentitySeed()
            val priv = withContext(Dispatchers.Default) { GeohashKeyDerivation.derivePrivateKey(seed, g) }
            val pub = GeohashKeyDerivation.pubkeyBytesFromPrivkey(priv).toHex()
            val nick = _nickname.value
            val tags = buildList {
                // Tag the exact code plus the 5-char city prefix, so Bitchat and
                // Amethyst users watching the city cell see our message too.
                GeohashRelays.postGeohashes(g).forEach { gh ->
                    add(buildJsonArray { add(JsonPrimitive("g")); add(JsonPrimitive(gh)) })
                }
                if (nick.isNotBlank()) {
                    add(buildJsonArray { add(JsonPrimitive("n")); add(JsonPrimitive(nick)) })
                }
            }
            val unsigned = UnsignedEvent(
                pubkey = pub,
                kind = 20000,
                content = text.trim(),
                tags = tags,
            )
            val id = unsigned.computeId()
            val sig = withContext(Dispatchers.Default) {
                GeohashKeyDerivation.schnorrSign(id.hexToBytes(), priv)
            }
            val event = Event(id, pub, unsigned.createdAt, 20000, unsigned.tags, unsigned.content, sig)
            if (!event.verify()) {
                _error.value = "failed to verify anonymous message"
                return@launch
            }
            val relays = GeohashRelays.closestRelays(g).toSet()
            relays.forEach { pool.addRelay(it) }
            pool.publishTo(event, relays)
            // Optimistic local add — you always see your own message.
            seen.add(event.id)
            appendMessage(
                GeohashMessage(event.id, event.pubkey, event.createdAt, event.content, nick, own = true),
            )
        }
    }

    private fun appendMessage(msg: GeohashMessage) {
        // Cache to Room for 24h retention
        viewModelScope.launch(Dispatchers.IO) {
            try {
                geohashDao.insert(
                    social.tbone.db.GeohashMessageEntity(
                        id = msg.id,
                        channelCode = currentGeohash,
                        pubkey = msg.pubkey,
                        createdAt = msg.createdAt,
                        content = msg.content,
                        nickname = msg.nickname,
                        own = msg.own,
                        cachedAt = System.currentTimeMillis(),
                    )
                )
            } catch (_: Exception) { }
        }
        _messages.update { current ->
            val merged = (current + msg).distinctBy { it.id }
                .sortedBy { it.createdAt }
            // Keep only the 40 newest — but displayed oldest → newest, so a
            // chat reads top-to-bottom like a normal conversation.
            merged.takeLast(MAX_MESSAGES)
        }
    }

    override fun onCleared() {
        leave()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
