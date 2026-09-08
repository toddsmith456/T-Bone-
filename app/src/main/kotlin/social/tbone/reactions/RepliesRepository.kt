package social.tbone.reactions

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
            accountRepository.activeAccount.collect { account ->
                activePubkey = account?.pubkey
                // Re-seed: counts are per-account-agnostic; repliedByMe is not,
                // so reset the "I replied" flags on account switch.
                _repliedByMe.value = emptySet()
            }
        }
        // Live collector: count replies as they stream from any subscription.
        scope.launch {
            pool.messages.collect { (_, msg) ->
                if (msg is RelayMessage.EventMessage
                    && msg.event.kind == EventKind.TEXT_NOTE
                    && msg.event.verify()
                ) {
                    val parentId = msg.event.parsedTags.replyEventId ?: return@collect
                    bump(parentId, msg.event)
                }
            }
        }
    }

    /** Subscribe to replies for any note IDs not yet tracked. */
    fun subscribeTo(eventIds: List<String>) {
        val newIds = eventIds.filter { subscribedCache.get(it) == null }
        if (newIds.isEmpty()) return
        newIds.forEach { subscribedCache.put(it, Unit) }
        val subId = pool.subscribe(
            listOf(Filter(eTags = newIds, kinds = listOf(EventKind.TEXT_NOTE))),
            label = "replies",
        )
        // Unsubscribe after EOSE — historical replies loaded; live handled above.
        scope.launch {
            withTimeoutOrNull(30_000) {
                pool.messages.first { (_, m) ->
                    m is RelayMessage.EndOfStoredEvents && m.subscriptionId == subId
                }
            }
            pool.unsubscribe(subId)
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
        _replies.update { it + (parentId to count) }

        val me = activePubkey
        if (me != null && reply.pubkey == me) {
            synchronized(repliedCache) { repliedCache.put(parentId, true) }
            _repliedByMe.update { it + parentId }
        }
    }
}
