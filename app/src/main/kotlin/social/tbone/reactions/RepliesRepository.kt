package social.tbone.reactions

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.account.AccountRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.replyEventId
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks NIP-10 reply counts per note and whether the active user replied.
 *
 * State: `Map<eventId, replyCount>` and `Set<eventId>` of notes the active
 * account replied to. Feed/thread/profile call [subscribeTo] when a batch of
 * notes becomes visible, and a live collector counts replies as they stream.
 */
@Singleton
class RepliesRepository @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in RepliesRepository")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val replyCountCache = LruCache<String, Int>(Tunables.REACTION_CACHE_SIZE)
    private val repliedCache = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE)
    private val subscribedCache = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)
    // Reply event IDs already counted — prevents the SAME reply from being
    // counted multiple times when it arrives via several subscriptions
    // (feed + replies sub + thread live) or from multiple relays.
    private val countedReplyIds = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE * 4)

    private val _replies = MutableStateFlow<Map<String, Int>>(emptyMap())
    val replies: StateFlow<Map<String, Int>> = _replies.asStateFlow()

    private val _repliedByMe = MutableStateFlow<Set<String>>(emptySet())
    val repliedByMe: StateFlow<Set<String>> = _repliedByMe.asStateFlow()

    @Volatile private var activePubkey: String? = null

    init {
        scope.launch {
            accountRepository.activeAccount
                .map { it?.pubkey }
                .distinctUntilChanged()
                .collect { pubkey ->
                    activePubkey = pubkey
                    // AccountRepository emits again for ordinary profile/relay
                    // edits. Only an actual account switch should clear this
                    // UI marker; likes/reposts and account metadata updates must
                    // not erase the reply context already shown in the feed.
                    _repliedByMe.value = emptySet()
                }
        }
        // Live collector: count replies as they stream from any subscription.
        scope.launch {
            pool.messages.collect { (_, msg) ->
                runCatching {
                    if (msg is RelayMessage.EventMessage
                        && (msg.event.kind == EventKind.TEXT_NOTE ||
                            msg.event.kind == EventKind.COMMENT)
                        && msg.event.verify()
                    ) {
                        val parentId = msg.event.parsedTags.replyEventId ?: return@runCatching
                        bump(parentId, msg.event)
                    }
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    Timber.w(t, "RepliesRepository message handling failed")
                }
            }
        }
    }

    /** Subscribe to replies for any note IDs not yet tracked. */
    fun subscribeTo(eventIds: List<String>) {
        val newIds = synchronized(subscribedCache) {
            eventIds.distinct().filter { id ->
                if (subscribedCache.get(id) != null) {
                    false
                } else {
                    subscribedCache.put(id, Unit)
                    true
                }
            }
        }
        if (newIds.isEmpty()) return
        val subId = pool.subscribe(
            listOf(
                Filter(
                    eTags = newIds,
                    kinds = listOf(EventKind.TEXT_NOTE, EventKind.COMMENT),
                ),
            ),
            label = "replies",
        )
        // Unsubscribe after EOSE — historical replies loaded; live handled above.
        scope.launch {
            try {
                val answeredRelays = mutableSetOf<String>()
                val expectedRelays = pool.relayStatuses.value
                    .count { it.value == RelayStatus.CONNECTED }
                    .coerceAtLeast(1)
                withTimeoutOrNull(30_000) {
                    pool.messages.first { (relayUrl, m) ->
                        if (m !is RelayMessage.EndOfStoredEvents || m.subscriptionId != subId) {
                            false
                        } else {
                            answeredRelays += relayUrl
                            answeredRelays.size >= expectedRelays
                        }
                    }
                }
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    private fun bump(parentId: String, reply: Event) {
        // Dedupe: each reply event is counted exactly once, no matter how
        // many subscriptions or relays delivered it.
        val isNew = synchronized(countedReplyIds) {
            if (countedReplyIds.get(reply.id) != null) {
                false
            } else {
                countedReplyIds.put(reply.id, true)
                true
            }
        }
        if (!isNew) return

        val count = synchronized(replyCountCache) {
            val next = (replyCountCache.get(parentId) ?: 0) + 1
            replyCountCache.put(parentId, next)
            next
        }
        _replies.update { current ->
            val trimmed = if (current.size >= Tunables.REACTION_CACHE_SIZE && parentId !in current) {
                current.entries.drop(current.size / 4).associate { it.key to it.value }
            } else {
                current
            }
            trimmed + (parentId to count)
        }

        val me = activePubkey
        if (me != null && reply.pubkey == me) {
            synchronized(repliedCache) { repliedCache.put(parentId, true) }
            _repliedByMe.update { current ->
                val trimmed = if (current.size >= Tunables.REACTION_CACHE_SIZE && parentId !in current) {
                    current.drop(current.size / 4).toSet()
                } else {
                    current
                }
                trimmed + parentId
            }
        }
    }
}
