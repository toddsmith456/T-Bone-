package social.tbone.reactions

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Option 5: Single batched coordinator for note engagement subscriptions.
 *
 * Previously each feed viewport slice, thread note, or profile post opened up
 * to three separate relay subscriptions (one for reactions, one for replies,
 * one for poll responses), and profile/thread streams fired them per single
 * arriving event.
 *
 * This coordinator:
 *  1. Deduplicates already-subscribed note and poll IDs app-wide.
 *  2. Coalesces rapid calls across a 100 ms window (flushing early at 40 IDs).
 *  3. Combines reactions (kind 7), replies (kinds 1 & 1111), and poll votes
 *     (kind 1018) into ONE multi-filter `REQ` per chunk, then unsubscribes
 *     cleanly once connected relays answer EOSE or timeout.
 *
 * [ReactionsRepository], [RepliesRepository], and [PollsRepository] all listen
 * passively on [RelayPool.messages], so events returned by these combined
 * subscriptions populate all three repositories automatically.
 */
@Singleton
class EngagementSubscriptionCoordinator @Inject constructor(
    private val pool: RelayPool,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in EngagementSubscriptionCoordinator")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    private val lock = Any()
    private val subscribedNotes = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)
    private val subscribedPolls = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)
    private val pendingNotes = LinkedHashSet<String>()
    private val pendingPolls = LinkedHashSet<String>()

    private val flushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val flushImmediate = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in flushDebounced) {
                delay(Tunables.ENGAGEMENT_BATCH_WINDOW_MS)
                flushPending()
            }
        }
        scope.launch {
            for (signal in flushImmediate) {
                flushPending()
            }
        }
    }

    /**
     * Queue reactions + replies subscriptions for [noteIds] and poll-vote
     * subscriptions for [pollIds]. Duplicate or already-subscribed IDs are
     * ignored; remaining IDs are batched into one combined relay REQ.
     */
    fun subscribeForNotes(
        noteIds: Collection<String>,
        pollIds: Collection<String> = emptyList(),
    ) {
        if (noteIds.isEmpty() && pollIds.isEmpty()) return
        var added = false
        var shouldFlushNow = false
        synchronized(lock) {
            for (raw in noteIds) {
                val id = raw.trim()
                if (id.isEmpty() || subscribedNotes.get(id) != null) continue
                subscribedNotes.put(id, Unit)
                if (pendingNotes.add(id)) added = true
            }
            for (raw in pollIds) {
                val id = raw.trim()
                if (id.isEmpty() || subscribedPolls.get(id) != null) continue
                subscribedPolls.put(id, Unit)
                if (pendingPolls.add(id)) added = true
            }
            shouldFlushNow =
                pendingNotes.size >= Tunables.ENGAGEMENT_BATCH_MAX ||
                pendingPolls.size >= Tunables.ENGAGEMENT_BATCH_MAX
        }
        if (!added) return
        if (shouldFlushNow) {
            flushImmediate.trySend(Unit)
        } else {
            flushDebounced.trySend(Unit)
        }
    }

    /** Queue kind-1018 poll-vote subscriptions only (coalesced with any pending note batch). */
    fun subscribeForPolls(pollIds: Collection<String>) {
        subscribeForNotes(noteIds = emptyList(), pollIds = pollIds)
    }

    private fun flushPending() {
        val (notes, polls) = synchronized(lock) {
            if (pendingNotes.isEmpty() && pendingPolls.isEmpty()) return
            val n = pendingNotes.toList().also { pendingNotes.clear() }
            val p = pendingPolls.toList().also { pendingPolls.clear() }
            n to p
        }
        val batches = chunkEngagementBatches(notes, polls, Tunables.ENGAGEMENT_BATCH_MAX)
        for (filters in batches) {
            openCombinedSubscription(filters)
        }
    }

    private fun openCombinedSubscription(filters: List<Filter>) {
        if (filters.isEmpty()) return
        val subId = pool.subscribe(filters, label = "engagement")
        scope.launch {
            try {
                val answeredRelays = mutableSetOf<String>()
                val expectedRelays = pool.relayStatuses.value
                    .count { it.value == RelayStatus.CONNECTED }
                    .coerceAtLeast(1)
                withTimeoutOrNull(Tunables.REACTION_EOSE_TIMEOUT_MS) {
                    pool.messages.first { (relayUrl, msg) ->
                        if (msg !is RelayMessage.EndOfStoredEvents || msg.subscriptionId != subId) {
                            false
                        } else {
                            answeredRelays += relayUrl
                            answeredRelays.size >= expectedRelays
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                Timber.w(t, "Engagement subscription %s failed while waiting for EOSE", subId)
            } finally {
                pool.unsubscribe(subId)
            }
        }
    }

    companion object {
        internal val NOTE_ENGAGEMENT_KINDS = listOf(
            EventKind.REACTION,
            EventKind.TEXT_NOTE,
            EventKind.COMMENT,
        )

        /**
         * Builds ONE combined multi-filter payload covering reactions (kind 7),
         * replies (kinds 1 & 1111), and poll responses (kind 1018).
         */
        internal fun buildEngagementFilters(
            noteIds: List<String>,
            pollIds: List<String>,
        ): List<Filter> = buildList {
            if (noteIds.isNotEmpty()) {
                add(Filter(eTags = noteIds, kinds = NOTE_ENGAGEMENT_KINDS))
            }
            if (pollIds.isNotEmpty()) {
                add(Filter(eTags = pollIds, kinds = listOf(EventKind.POLL_RESPONSE)))
            }
        }

        /**
         * Splits arbitrarily large note/poll ID sets into relay-safe combined
         * filter batches, pairing each note chunk with a poll chunk in the
         * same `REQ` so total subscription count stays minimal.
         */
        internal fun chunkEngagementBatches(
            noteIds: List<String>,
            pollIds: List<String>,
            maxPerFilter: Int = Tunables.ENGAGEMENT_BATCH_MAX,
        ): List<List<Filter>> {
            val step = maxPerFilter.coerceAtLeast(1)
            val noteChunks = noteIds.distinct().chunked(step)
            val pollChunks = pollIds.distinct().chunked(step)
            val count = maxOf(noteChunks.size, pollChunks.size)
            if (count == 0) return emptyList()
            return List(count) { i ->
                buildEngagementFilters(
                    noteIds = noteChunks.getOrElse(i) { emptyList() },
                    pollIds = pollChunks.getOrElse(i) { emptyList() },
                )
            }
        }
    }
}
