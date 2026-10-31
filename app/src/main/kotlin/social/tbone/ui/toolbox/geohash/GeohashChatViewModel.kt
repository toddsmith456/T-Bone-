package social.tbone.ui.toolbox.geohash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.onSubscription
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
    private val cache: GeohashMessageCache,
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
    private var relayJoinJob: Job? = null
    @Volatile private var currentGeohash: String = ""
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
        pinnedMonitor.open(g)
        seen.clear()
        _messages.value = emptyList()
        _error.value = null
        _live.value = false

        relayJoinJob = viewModelScope.launch {
            val ownKeys = cache.ownPubkeys(g)
            if (currentGeohash != g) return@launch
            val cached = cache.load(g).map { it.copy(own = it.own || it.pubkey in ownKeys) }
            if (currentGeohash != g) return@launch
            cached.forEach { seen.add(it.id) }
            _messages.update { current -> (current + cached).distinctBy { it.id }.sortedBy { it.createdAt } }
            val relays = GeohashRelays.closestRelays(g).toSet()
            relays.forEach { if (addedRelays.add(it)) pool.acquireRelay(it) }
            val id = java.util.UUID.randomUUID().toString().take(8)
            subId = id
            collectJob = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                pool.messages.onSubscription {
                    pool.subscribeTo(listOf(Filter(kinds = listOf(20000), gTags = GeohashRelays.watchGeohashes(g),
                        limit = MAX_MESSAGES)), id = id, label = "geohash", relays = relays)
                }.collect { poolMessage ->
                    when (val msg = poolMessage.message) {
                        is RelayMessage.EndOfStoredEvents -> if (msg.subscriptionId == id) _live.value = true
                        is RelayMessage.EventMessage -> {
                            if (msg.subscriptionId != id || currentGeohash != g) return@collect
                            val event = msg.event
                            if (event.kind != 20000 || !event.matchesGeohashChannel(g) || !event.verify()) return@collect
                            if (!seen.add(event.id)) return@collect
                            val message = GeohashMessage(event.id, event.pubkey, event.createdAt, event.content,
                                event.parsedTags.firstOrNull { it.name == "n" }?.value(), event.pubkey in ownKeys)
                            appendMessage(g, message)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    fun leave() {
        relayJoinJob?.cancel()
        relayJoinJob = null
        collectJob?.cancel()
        collectJob = null
        subId?.let { pool.unsubscribe(it) }
        subId = null
        val previous = currentGeohash
        currentGeohash = ""
        if (previous.isNotEmpty()) pinnedMonitor.close(previous)
        _live.value = false
        // Drop the relays this chat connected (other channels re-add theirs).
        addedRelays.forEach { pool.releaseRelay(it) }
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
            if (currentGeohash != g) return@launch
            // Mark before publish, so a fast relay echo cannot be mistaken for another sender.
            seen.add(event.id)
            appendMessage(g, GeohashMessage(event.id, event.pubkey, event.createdAt, event.content, nick, own = true))
            pool.publishTo(event, relays)
        }
    }

    private fun appendMessage(channel: String, msg: GeohashMessage) {
        cache.enqueue(channel, msg)
        if (currentGeohash != channel) return
        _messages.update { current -> (current + msg).distinctBy { it.id }.sortedBy { it.createdAt } }
    }

    override fun onCleared() {
        leave()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
