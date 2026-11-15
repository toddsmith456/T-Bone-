package social.tbone.reactions

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.Tunables
import social.tbone.account.AccountRepository
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.replyEventId
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
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
    private val engagementCoordinator: EngagementSubscriptionCoordinator,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in RepliesRepository")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val replyCountCache = LruCache<String, Int>(Tunables.REACTION_CACHE_SIZE)
    private val repliedCache = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE)
    // Reply event IDs already counted — prevents the SAME reply from being
    // counted multiple times when it arrives via several subscriptions
    // (feed + replies sub + thread live) or from multiple relays.
    private val countedReplyIds = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE * 4)

    private val _replies = MutableStateFlow<Map<String, Int>>(emptyMap())
    val replies: StateFlow<Map<String, Int>> = _replies.asStateFlow()

    private val _repliedByMe = MutableStateFlow<Set<String>>(emptySet())
    val repliedByMe: StateFlow<Set<String>> = _repliedByMe.asStateFlow()

    // Option 2: 50ms conflated settle window coalesces bursty reply streams into
    // a single StateFlow update instead of copying the full map per reply event.
    private val pendingLock = Any()
    private val pendingReplyCounts = LinkedHashMap<String, Int>()
    private val pendingRepliedByMe = LinkedHashSet<String>()
    private val uiDirty = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var activePubkey: String? = null

    init {
        scope.launch {
            for (signal in uiDirty) {
                delay(Tunables.UI_BATCH_WINDOW_MS)
                flushUiUpdates()
            }
        }
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
                    synchronized(pendingLock) { pendingRepliedByMe.clear() }
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

    private fun flushUiUpdates() {
        val (replyDelta, myDelta) = synchronized(pendingLock) {
            val r = if (pendingReplyCounts.isEmpty()) emptyMap() else pendingReplyCounts.toMap().also { pendingReplyCounts.clear() }
            val m = if (pendingRepliedByMe.isEmpty()) emptySet() else pendingRepliedByMe.toSet().also { pendingRepliedByMe.clear() }
            r to m
        }
        if (replyDelta.isNotEmpty()) {
            _replies.update { current ->
                val merged = current + replyDelta
                if (merged.size > Tunables.REACTION_CACHE_SIZE) {
                    merged.entries.drop(merged.size - Tunables.REACTION_CACHE_SIZE + Tunables.REACTION_CACHE_SIZE / 4)
                        .associate { it.key to it.value }
                } else merged
            }
        }
        if (myDelta.isNotEmpty()) {
            _repliedByMe.update { current ->
                val merged = current + myDelta
                if (merged.size > Tunables.REACTION_CACHE_SIZE) {
                    merged.drop(merged.size - Tunables.REACTION_CACHE_SIZE + Tunables.REACTION_CACHE_SIZE / 4).toSet()
                } else merged
            }
        }
    }

    /** Subscribe to replies for any note IDs not yet tracked (batched + combined). */
    fun subscribeTo(eventIds: List<String>) {
        engagementCoordinator.subscribeForNotes(eventIds)
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
        val isOwnReply = activePubkey != null && reply.pubkey == activePubkey
        if (isOwnReply) {
            synchronized(repliedCache) { repliedCache.put(parentId, true) }
        }
        synchronized(pendingLock) {
            pendingReplyCounts[parentId] = count
            if (isOwnReply) pendingRepliedByMe.add(parentId)
        }
        uiDirty.trySend(Unit)
    }
}
