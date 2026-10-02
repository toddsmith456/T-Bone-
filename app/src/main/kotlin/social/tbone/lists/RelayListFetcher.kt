package social.tbone.lists

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import social.tbone.Tunables
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot, READ-ONLY fetch of an account's newest replaceable list event
 * (kind 3 follow list / kind 10000 mute list) from the relays.
 *
 * Used for:
 *  - the very first sync when an offline list is switched on,
 *  - the manual "sync online → offline" button,
 *  - re-reading the latest relay list right before publishing a change, so a
 *    stale cached copy can never overwrite the real list.
 *
 * It only ever sends a REQ; it never publishes anything.
 */
@Singleton
class RelayListFetcher @Inject constructor(
    private val pool: RelayPool,
) {
    /**
     * @property event       newest verified event found, or null when no relay has one
     * @property eoseRelays  how many relays answered (EOSE). 0 means we heard nothing at
     *                       all — relays unreachable — so "no event" must NOT be read as
     *                       "the list is empty".
     */
    data class Result(val event: Event?, val eoseRelays: Int) {
        val reachedRelays: Boolean get() = eoseRelays > 0 || event != null
    }

    suspend fun fetchLatest(
        pubkey: String,
        kind: Int,
        seedRelays: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result = coroutineScope {
        // Make sure there is something to talk to (a fresh install may have no relays yet).
        if (pool.relayUrls().isEmpty()) {
            seedRelays.ifEmpty { Tunables.DEFAULT_RELAYS }.forEach { pool.addRelay(it) }
        }

        val eose: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val best = AtomicReference<Event?>(null)
        val subId = "ll-" + UUID.randomUUID().toString().take(8)
        val ready = CompletableDeferred<Unit>()

        val collector: Job = launch(Dispatchers.Default) {
            pool.messages
                .onSubscription {
                    pool.subscribe(
                        listOf(Filter(authors = listOf(pubkey), kinds = listOf(kind), limit = 1)),
                        id = subId,
                        label = "list-fetch",
                    )
                    ready.complete(Unit)
                }
                .collect { pm ->
                    when (val m = pm.message) {
                        is RelayMessage.EventMessage -> {
                            if (m.subscriptionId == subId) {
                                val e = m.event
                                if (e.pubkey == pubkey && e.kind == kind && e.verify()) {
                                    best.updateAndGet { cur -> if (cur == null || e.createdAt > cur.createdAt) e else cur }
                                }
                            }
                        }
                        is RelayMessage.EndOfStoredEvents -> if (m.subscriptionId == subId) eose.add(pm.relayUrl)
                        is RelayMessage.Closed -> if (m.subscriptionId == subId) eose.add(pm.relayUrl)
                        else -> Unit
                    }
                }
        }

        try {
            ready.await()
            val start = System.currentTimeMillis()
            var graceStart = 0L
            while (true) {
                delay(POLL_MS)
                val now = System.currentTimeMillis()
                val expected = pool.relayUrls().size.coerceAtLeast(1)
                if (eose.size >= expected) break                 // everyone answered
                if (eose.isNotEmpty()) {
                    if (graceStart == 0L) graceStart = now
                    if (now - graceStart >= GRACE_AFTER_FIRST_EOSE_MS) break // stragglers: stop waiting
                }
                if (now - start >= timeoutMs) break
            }
        } finally {
            collector.cancel()
            pool.unsubscribe(subId)
        }
        Timber.d("List fetch kind=$kind: event=${best.get()?.id?.take(8)} eose=${eose.size}")
        Result(best.get(), eose.size)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val POLL_MS = 100L
        private const val GRACE_AFTER_FIRST_EOSE_MS = 3_000L
    }
}
