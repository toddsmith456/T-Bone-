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
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
import social.tbone.nostr.relay.ClientMessage
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks NIP-88 poll votes app-wide and publishes new votes.
 *
 * Counts and voters are cached per poll; the active user's last choice is also
 * persisted per account so results stay visible after navigation or a restart.
 */
@Singleton
class PollsRepository @Inject constructor(
    private val pool: RelayPool,
    private val signerFactory: NostrSignerFactory,
    private val accountRepository: AccountRepository,
    private val pollVoteStore: PollVoteStore,
) {
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "Uncaught exception in PollsRepository")
            },
    )

    private val pollLock = Any()
    private val countsCache = LruCache<String, MutableMap<String, Int>>(Tunables.REACTION_CACHE_SIZE)
    private val votersCache = LruCache<String, MutableMap<String, Pair<Long, List<String>>>>(Tunables.REACTION_CACHE_SIZE)
    private val myVotesCache = LruCache<String, List<String>>(Tunables.REACTION_CACHE_SIZE)
    private val subscribedCache = LruCache<String, Unit>(Tunables.REACTION_CACHE_SIZE)
    private val seenVotes = LruCache<String, Boolean>(Tunables.REACTION_CACHE_SIZE * 4)

    private val _counts = MutableStateFlow<Map<String, Map<String, Int>>>(emptyMap())
    val counts: StateFlow<Map<String, Map<String, Int>>> = _counts.asStateFlow()
    private val _totalVotes = MutableStateFlow<Map<String, Int>>(emptyMap())
    val totalVotes: StateFlow<Map<String, Int>> = _totalVotes.asStateFlow()

    private val _myVotes = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val myVotes: StateFlow<Map<String, List<String>>> = _myVotes.asStateFlow()

    private val _voteVersion = MutableStateFlow(0)
    /** Bumped whenever any poll's votes change — screens key recomputes on this. */
    val voteVersion: StateFlow<Int> = _voteVersion.asStateFlow()

    @Volatile private var activePubkey: String? = null

    init {
        scope.launch {
            accountRepository.activeAccount.distinctUntilChangedBy { it?.pubkey }.collect { account ->
                activePubkey = account?.pubkey
                synchronized(pollLock) { myVotesCache.evictAll() }
                _myVotes.value = emptyMap()
                if (account != null) {
                    val stored = runCatching { pollVoteStore.getVotes(account.pubkey) }
                        .onFailure { Timber.w(it, "Could not restore saved poll votes") }
                        .getOrDefault(emptyMap())
                    if (activePubkey == account.pubkey) {
                        stored.forEach { (pollId, vote) ->
                            if (activePubkey != account.pubkey) return@forEach
                            val optionIds = vote.optionIds.distinct()
                            if (optionIds.isEmpty()) return@forEach
                            // Apply persisted state to the counts, then prefer a
                            // newer relay vote if one arrived during restoration.
                            applyVote(pollId, account.pubkey, vote.createdAt, optionIds)
                            val effective = currentVote(pollId, account.pubkey)
                                ?: PersistedPollVote(vote.createdAt, optionIds)
                            synchronized(pollLock) { myVotesCache.put(pollId, effective.optionIds) }
                            _myVotes.update { it + (pollId to effective.optionIds) }
                        }
                    }
                }
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

    fun getCounts(pollId: String): Map<String, Int> = synchronized(pollLock) {
        countsCache.get(pollId)?.toMap() ?: emptyMap()
    }

    fun getTotalVotes(pollId: String): Int = synchronized(pollLock) {
        votersCache.get(pollId)?.size ?: 0
    }

    fun getMyVotes(pollId: String): List<String> = synchronized(pollLock) {
        myVotesCache.get(pollId)?.toList() ?: emptyList()
    }

    private fun ingest(vote: Event, pollId: String) {
        val optionIds = runCatching { Nip88.getResponseOptionIds(vote).distinct() }
            .getOrDefault(emptyList())
        if (optionIds.isEmpty()) return
        // Dedupe identical vote events (relay duplication).
        synchronized(seenVotes) {
            if (seenVotes.get(vote.id) != null) return
            seenVotes.put(vote.id, true)
        }
        if (!applyVote(pollId, vote.pubkey, vote.createdAt, optionIds)) return

        val me = activePubkey
        if (me != null && vote.pubkey == me) {
            scope.launch {
                runCatching {
                    pollVoteStore.saveVote(me, pollId, PersistedPollVote(vote.createdAt, optionIds))
                }.onFailure { Timber.w(it, "Could not save poll vote") }
            }
        }
    }

    /** Updates vote counts if this is newer than the voter's previous response. */
    private fun applyVote(
        pollId: String,
        pubkey: String,
        createdAt: Long,
        optionIds: List<String>,
    ): Boolean {
        val cleanOptionIds = optionIds.distinct()
        if (cleanOptionIds.isEmpty()) return false
        var snapshot: Map<String, Int> = emptyMap()
        var total = 0
        val changed = synchronized(pollLock) {
            val voters = votersCache.get(pollId)
                ?: mutableMapOf<String, Pair<Long, List<String>>>().also { votersCache.put(pollId, it) }
            val counts = countsCache.get(pollId)
                ?: mutableMapOf<String, Int>().also { countsCache.put(pollId, it) }
            val previous = voters[pubkey]
            if (previous != null && createdAt <= previous.first) {
                false
            } else {
                // Decrement old option counts when a pubkey re-votes.
                previous?.second?.forEach { oldOption ->
                    val count = counts[oldOption]
                    if (count != null && count > 0) counts[oldOption] = count - 1
                }
                voters[pubkey] = createdAt to cleanOptionIds
                cleanOptionIds.forEach { optionId -> counts[optionId] = (counts[optionId] ?: 0) + 1 }
                snapshot = counts.toMap()
                total = voters.size
                if (pubkey == activePubkey) {
                    myVotesCache.put(pollId, cleanOptionIds)
                }
                true
            }
        }
        if (!changed) return false

        _counts.update { it + (pollId to snapshot) }
        _totalVotes.update { it + (pollId to total) }
        if (pubkey == activePubkey) {
            _myVotes.update { it + (pollId to cleanOptionIds) }
        }
        _voteVersion.update { it + 1 }
        return true
    }

    private fun currentVote(pollId: String, pubkey: String): PersistedPollVote? = synchronized(pollLock) {
        votersCache.get(pollId)?.get(pubkey)?.let { PersistedPollVote(it.first, it.second) }
    }

    /** Reverts one optimistic choice only if a newer vote has not replaced it. */
    private fun rollbackVote(
        pollId: String,
        pubkey: String,
        optimisticCreatedAt: Long,
        previous: PersistedPollVote?,
    ) {
        var snapshot: Map<String, Int> = emptyMap()
        var total = 0
        val changed = synchronized(pollLock) {
            val voters = votersCache.get(pollId) ?: return
            val counts = countsCache.get(pollId)
                ?: mutableMapOf<String, Int>().also { countsCache.put(pollId, it) }
            val current = voters[pubkey]
            if (current?.first != optimisticCreatedAt) {
                false
            } else {
                current.second.forEach { optionId ->
                    val count = counts[optionId]
                    if (count != null && count > 0) counts[optionId] = count - 1
                }
                if (previous == null) {
                    voters.remove(pubkey)
                } else {
                    voters[pubkey] = previous.createdAt to previous.optionIds
                    previous.optionIds.forEach { optionId -> counts[optionId] = (counts[optionId] ?: 0) + 1 }
                }
                snapshot = counts.toMap()
                total = voters.size
                if (activePubkey == pubkey) {
                    if (previous == null) myVotesCache.remove(pollId)
                    else myVotesCache.put(pollId, previous.optionIds)
                }
                true
            }
        }
        if (!changed) return

        _counts.update { it + (pollId to snapshot) }
        _totalVotes.update { it + (pollId to total) }
        if (activePubkey == pubkey) {
            if (previous == null) {
                _myVotes.update { it - pollId }
            } else {
                _myVotes.update { it + (pollId to previous.optionIds) }
            }
        }
        _voteVersion.update { it + 1 }
        scope.launch {
            runCatching {
                pollVoteStore.restoreAfterFailure(pubkey, pollId, optimisticCreatedAt, previous)
            }.onFailure { Timber.w(it, "Could not roll back saved poll vote") }
        }
    }

    /** Casts a vote (kind 1018) on [poll] for the given option ids. */
    fun vote(poll: Event, optionIds: List<String>) {
        val me = activePubkey ?: return
        if (Nip88.isPollEnded(poll)) return
        val options = Nip88.parsePollOptions(poll)
        val validOptionIds = options.mapTo(mutableSetOf()) { it.id }
        var selected = optionIds.distinct().filter { it in validOptionIds }
        if (Nip88.parsePollType(poll) == Nip88.PollType.SINGLECHOICE) selected = selected.take(1)
        if (selected.isEmpty()) return

        val previous = currentVote(poll.id, me)
        if (previous != null && previous.optionIds.toSet() == selected.toSet()) return
        val now = System.currentTimeMillis() / 1000L
        val optimisticCreatedAt = maxOf(now, (previous?.createdAt ?: 0L) + 1L)
        val optimistic = PersistedPollVote(optimisticCreatedAt, selected)
        if (!applyVote(poll.id, me, optimistic.createdAt, optimistic.optionIds)) return

        scope.launch {
            try {
                val signer = signerFactory.forActiveAccount() ?: error("No signing account available")
                if (signer.pubkey != me) error("The active account changed before the vote could be signed")
                val tags = Nip88.buildResponseTags(poll.id, selected).map { tag ->
                    kotlinx.serialization.json.buildJsonArray {
                        tag.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                    }
                }
                val unsigned = UnsignedEvent(
                    pubkey = signer.pubkey,
                    createdAt = optimisticCreatedAt,
                    kind = EventKind.POLL_RESPONSE,
                    content = "",
                    tags = tags,
                )
                val event = signer.signEvent(unsigned).getOrElse { throw it }
                // Save the signed choice before publishing; a restart immediately
                // after the tap still restores the selected result view.
                runCatching {
                    pollVoteStore.saveVote(me, poll.id, PersistedPollVote(event.createdAt, selected))
                }.onFailure { Timber.w(it, "Could not save poll vote") }
                pool.publish(event)
                // Also send to the poll's specified relays per NIP-88.
                Nip88.parsePollRelays(poll).forEach { url ->
                    runCatching { pool.send(url, ClientMessage.Publish(event)) }
                }
                // Optimistic counts are already applied; ingest handles relay
                // echoes and deduplicates them without double-counting.
                ingest(event, poll.id)
            } catch (cancelled: CancellationException) {
                rollbackVote(poll.id, me, optimisticCreatedAt, previous)
                throw cancelled
            } catch (error: Exception) {
                rollbackVote(poll.id, me, optimisticCreatedAt, previous)
                Timber.w(error, "Poll vote failed")
            }
        }
    }
}
