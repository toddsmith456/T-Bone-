package social.tbone.reactions

import android.util.LruCache
import social.tbone.Tunables
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks NIP-25 reactions (kind-7, content="+") across the app.
 *
 * State is stored as `Map<eventId, Set<reactorPubkey>>` so callers can
 * derive both the count and whether the active user has reacted.
 *
 * Call [subscribeTo] whenever a new batch of events becomes visible.
 * Call [react] to post a "+" reaction (with optimistic update + rollback).
 */
@Singleton
class ReactionsRepository @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Caps reaction data; oldest entries evicted automatically.
    private val reactionCache = LruCache<String, Set<String>>(Tunables.REACTION_CACHE_SIZE)
    private val subscribedCache = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)

    private val _reactions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val reactions: StateFlow<Map<String, Set<String>>> = _reactions.asStateFlow()

    /**
     * The reaction content each pubkey left on each note, keyed by
     * [reactionKey] (`eventId|pubkey`). Used to show the emoji the active user
     * reacted with in place of the heart.
     */
    private val contentCache = LruCache<String, String>(Tunables.REACTION_CACHE_SIZE)
    // Relays do not guarantee ordering for multiple reactions by one pubkey.
    // Keep the newest event's content so an older emoji arriving late cannot
    // replace the emoji the user actually used most recently.
    private val contentTimestampCache = LruCache<String, Long>(Tunables.REACTION_CACHE_SIZE)
    private val _reactionContents = MutableStateFlow<Map<String, String>>(emptyMap())
    val reactionContents: StateFlow<Map<String, String>> = _reactionContents.asStateFlow()

    private fun recordContent(eventId: String, pubkey: String, content: String, createdAt: Long) {
        val key = reactionKey(eventId, pubkey)
        val shouldRecord = synchronized(contentTimestampCache) {
            val previous = contentTimestampCache.get(key)
            if (previous != null && previous > createdAt) {
                false
            } else {
                contentTimestampCache.put(key, createdAt)
                synchronized(contentCache) { contentCache.put(key, content) }
                true
            }
        }
        if (!shouldRecord) return
        _reactionContents.update { current ->
            // Keep the exposed map bounded like the LRU behind it.
            val trimmed = if (current.size >= Tunables.REACTION_CACHE_SIZE) {
                current.entries.drop(current.size / 4).associate { it.key to it.value }
            } else current
            trimmed + (key to content)
        }
    }

    init {
        scope.launch {
            pool.messages.collect { (_, msg) ->
                // Any positive reaction counts: "+" (or empty) is a like, and
                // an emoji is a like shown as that emoji. "-" is a dislike.
                if (msg is RelayMessage.EventMessage
                    && msg.event.kind == EventKind.REACTION
                    && msg.event.content != "-"
                    && msg.event.verify()
                ) {
                    val targetId = msg.event.parsedTags
                        .lastOrNull { it.name == "e" }?.value() ?: return@collect
                    val updated = synchronized(reactionCache) {
                        val next = (reactionCache.get(targetId) ?: emptySet()) + msg.event.pubkey
                        reactionCache.put(targetId, next)
                        next
                    }
                    // Targeted update: replace only the changed entry instead of
                    // snapshotting the entire 5000-entry cache on every reaction event.
                    _reactions.update { it + (targetId to updated) }
                    recordContent(
                        targetId,
                        msg.event.pubkey,
                        msg.event.content.ifEmpty { "+" },
                        msg.event.createdAt,
                    )
                }
            }
        }
    }

    /** Subscribe to reactions for any event IDs not yet tracked. */
    fun subscribeTo(eventIds: List<String>) {
        val newIds = eventIds.filter { subscribedCache.get(it) == null }
        if (newIds.isEmpty()) return
        newIds.forEach { subscribedCache.put(it, Unit) }
        val subId = pool.subscribe(
            listOf(Filter(eTags = newIds, kinds = listOf(EventKind.REACTION))),
            label = "reactions",
        )
        // Unsubscribe after EOSE — historical data loaded; live reactions handled by the init collector
        scope.launch {
            val answeredRelays = mutableSetOf<String>()
            val expectedRelays = pool.relayStatuses.value
                .count { it.value == RelayStatus.CONNECTED }
                .coerceAtLeast(1)
            withTimeoutOrNull(30_000) {
                pool.messages.first { (relayUrl, msg) ->
                    if (msg !is RelayMessage.EndOfStoredEvents || msg.subscriptionId != subId) {
                        false
                    } else {
                        answeredRelays += relayUrl
                        answeredRelays.size >= expectedRelays
                    }
                }
            }
            pool.unsubscribe(subId)
        }
    }

    /** Publish a "+" reaction. Updates state optimistically; rolls back on failure. */
    fun react(event: Event, content: String = "+") {
        val reaction = content.trim().ifEmpty { "+" }
        scope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val activePubkey = signer.pubkey

            val optimistic = synchronized(reactionCache) {
                val next = (reactionCache.get(event.id) ?: emptySet()) + activePubkey
                reactionCache.put(event.id, next)
                next
            }
            _reactions.update { it + (event.id to optimistic) }
            recordContent(event.id, activePubkey, reaction, System.currentTimeMillis() / 1000)

            val unsigned = UnsignedEvent(
                pubkey = activePubkey,
                kind = EventKind.REACTION,
                content = reaction,
                tags = listOf(
                    buildJsonArray { add("e"); add(event.id) },
                    buildJsonArray { add("p"); add(event.pubkey) },
                    buildJsonArray { add("k"); add(event.kind.toString()) },
                ),
            )
            signer.signEvent(unsigned)
                .onSuccess { pool.publish(it) }
                .onFailure { e ->
                    val rollback = synchronized(reactionCache) {
                        val next = (reactionCache.get(event.id) ?: emptySet()) - activePubkey
                        reactionCache.put(event.id, next)
                        next
                    }
                    _reactions.update { it + (event.id to rollback) }
                    val key = reactionKey(event.id, activePubkey)
                    synchronized(contentCache) { contentCache.remove(key) }
                    synchronized(contentTimestampCache) { contentTimestampCache.remove(key) }
                    _reactionContents.update { it - key }
                    Timber.w(e, "React failed")
                }
        }
    }
}

/** Key for [ReactionsRepository.reactionContents]. */
fun reactionKey(eventId: String, pubkey: String): String = "$eventId|$pubkey"
