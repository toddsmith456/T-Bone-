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
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip88
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks NIP-88 poll votes app-wide and publishes new votes.
 *
 * State mirrors Wisp's EventRepository poll tracking:
 *  - counts:  poll id -> (option id -> count)
 *  - voters:  poll id -> (pubkey -> (created_at, option ids))  — one vote per
 *    pubkey, latest timestamp wins; re-votes decrement the old counts.
 *  - myVotes: poll id -> option ids the active user chose.
 *
 * Call [subscribeTo] when polls become visible, and [vote] to cast a vote.
 */
@Singleton
class PollsRepository @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val accountRepository: AccountRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val countsCache = LruCache<String, MutableMap<String, Int>>(Tunables.REACTION_CACHE_SIZE)
    private val votersCache = LruCache<String, MutableMap<String, Pair<Long, List<String>>>>(Tunables.REACTION_CACHE_SIZE)
    private val myVotesCache = LruCache<String, List<String>>(Tunables.REACTION_CACHE_SIZE)
    private val subscribedCache = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)
    private val seenVotes = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE * 4)

    private val _counts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val counts: StateFlow<Map<String, Map<String, Int>>> = _counts.asStateFlow()

    private val _myVotes = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val myVotes: StateFlow<Map<String, List<String>>> = _myVotes.asStateFlow()

    private val _voteVersion = MutableStateFlow(0)
    /** Bumped whenever any poll's votes change — screens key recomputes on this. */
    val voteVersion: StateFlow<Int> = _voteVersion.asStateFlow()

    @Volatile private var activePubkey: String? = null

    init {
        scope.launch {
            accountRepository.activeAccount.collect { account ->
                activePubkey = account?.pubkey
                // myVotes are per-account; reset on switch.
                _myVotes.value = emptyMap()
            }
        }
        // Live collector: count poll responses from any subscription.
        scope.launch {
            pool.messages.collect { (_, msg) ->
                runCatching {
                    if (msg is RelayMessage.EventMessage
                        && msg.event.kind == EventKind.POLL_RESPONSE
                        && msg.event.verify()
                    ) {
                        val pollId = Nip88.getPollEventId(msg.event) ?: return@collect
                        ingest(msg.event, pollId)
                    }
                }
            }
        }
    }

    /** Subscribe to poll responses for any poll ids not yet tracked. */
    fun subscribeTo(pollIds: List<String>) {
        val newIds = pollIds.filter { subscribedCache.get(it) == null }
        if (newIds.isEmpty()) return
        newIds.forEach { subscribedCache.put(it, Unit) }
        val subId = pool.subscribe(
            listOf(Filter(eTags = newIds, kinds = listOf(EventKind.POLL_RESPONSE))),
            label = "polls",
        )
        // Historical responses loaded; live handled by the init collector.
        scope.launch {
            withTimeoutOrNull(30_000) {
                pool.messages.first { (_, m) ->
                    m is RelayMessage.EndOfStoredEvents && m.subscriptionId == subId
                }
            }
            pool.unsubscribe(subId)
        }
    }

    fun getCounts(pollId: String): Map<String, Int> =
        countsCache.get(pollId)?.toMap() ?: emptyMap()

    fun getTotalVotes(pollId: String): Int =
        votersCache.get(pollId)?.size ?: 0

    fun getMyVotes(pollId: String): List<String> =
        myVotesCache.get(pollId) ?: emptyList()

    private fun ingest(vote: Event, pollId: String) {
        val optionIds = runCatching { Nip88.getResponseOptionIds(vote) }.getOrDefault(emptyList())
        if (optionIds.isEmpty()) return
        // Dedupe identical vote events (relay duplication).
        synchronized(seenVotes) {
            if (seenVotes.get(vote.id) != null) return
            seenVotes.put(vote.id, true)
        }
        // One vote per pubkey — latest timestamp wins.
        val voters = votersCache.get(pollId)
            ?: mutableMapOf<String, Pair<Long, List<String>>>().also { votersCache.put(pollId, it) }
        val counts = countsCache.get(pollId)
            ?: mutableMapOf<String, Int>().also { countsCache.put(pollId, it) }

        synchronized(voters) {
            val prev = voters[vote.pubkey]
            if (prev != null && vote.createdAt <= prev.first) return

            // Decrement old option counts when a pubkey re-votes.
            if (prev != null) {
                for (oldOption in prev.second) {
                    val c = counts[oldOption]
                    if (c != null && c > 0) counts[oldOption] = c - 1
                }
            }
            voters[vote.pubkey] = Pair(vote.createdAt, optionIds)
            for (optionId in optionIds) {
                counts[optionId] = (counts[optionId] ?: 0) + 1
            }
        }

        _counts.update { it + (pollId to counts.toMap()) }

        val me = activePubkey
        if (me != null && vote.pubkey == me) {
            myVotesCache.put(pollId, optionIds)
            _myVotes.update { it + (pollId to optionIds) }
        }

        _voteVersion.update { it + 1 }
    }

    /** Casts a vote (kind 1018) on [poll] for the given option ids. */
    fun vote(poll: Event, optionIds: List<String>) {
        if (optionIds.isEmpty()) return
        val me = activePubkey ?: return
        val existing = myVotesCache.get(poll.id)
        if (existing != null && existing.toSet() == optionIds.toSet()) return // already voted this way

        scope.launch {
            val signer = signerFactory.forActiveAccount() ?: return@launch
            val tagJson = runCatching {
                Nip88.buildResponseTags(poll.id, optionIds).map { tag ->
                    kotlinx.serialization.json.buildJsonArray {
                        tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                }
            }.getOrElse { return@launch }
            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.POLL_RESPONSE,
                content = "",
                tags = tagJson,
            )
            signer.signEvent(unsigned)
                .onSuccess { event ->
                    pool.publish(event)
                    // Also send to the poll's specified relays per NIP-88.
                    Nip88.parsePollRelays(poll).forEach { url ->
                        runCatching { pool.send(url, social.tbone.nostr.relay.ClientMessage.Publish(event)) }
                    }
                    // Optimistic local update.
                    ingest(event, poll.id)
                }
                .onFailure { e -> Timber.w(e, "Poll vote failed") }
        }
    }
}
