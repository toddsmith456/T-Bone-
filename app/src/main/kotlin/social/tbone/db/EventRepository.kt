package social.tbone.db

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import social.tbone.Tunables
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(private val dao: EventDao) {

    private val writeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Uncaught exception in EventRepository batch writer")
        },
    )
    private val writeLock = Any()
    private val pendingWrites = LinkedHashMap<String, Pair<Event, String>>()
    private val flushDebounced = Channel<Unit>(Channel.CONFLATED)
    private val flushImmediate = Channel<Unit>(Channel.CONFLATED)

    init {
        writeScope.launch {
            for (signal in flushDebounced) {
                delay(Tunables.DB_WRITE_BATCH_MS)
                flushPendingWrites()
            }
        }
        writeScope.launch {
            for (signal in flushImmediate) {
                flushPendingWrites()
            }
        }
    }

    fun feedEvents(accountPubkey: String): Flow<List<Event>> = dao.observeByKinds(
        listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.GENERIC_REPOST, EventKind.POLL), accountPubkey
    ).map { list -> list.mapNotNull { runCatching { it.toEvent() }.getOrNull() } }

    suspend fun getRecentFeedEvents(accountPubkey: String, limit: Int = 300): List<Event> =
        dao.getRecentByKinds(
            listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.GENERIC_REPOST, EventKind.POLL),
            accountPubkey,
            limit,
        ).mapNotNull { runCatching { it.toEvent() }.getOrNull() }

    /** Immediate single-event persistence (used for bookmarks and synchronous side-loads). */
    suspend fun save(event: Event, accountPubkey: String) {
        // Side-loads (threads, quotes and notifications) pass an empty account
        // because they are not feed-owned. Do not let that erase the account
        // association of an event already cached by a feed.
        val existing = dao.getById(event.id)
        val owner = accountPubkey.ifBlank { existing?.accountPubkey ?: "" }
        dao.upsert(event.toEntity(owner))
    }

    /**
     * Queues an event for write-behind batch persistence.
     * Rapid feed bursts coalesce into a single Room @Upsert transaction after
     * [Tunables.DB_WRITE_BATCH_MS] (or immediately once [Tunables.DB_WRITE_BATCH_MAX]
     * events are queued), preventing per-event SQLite lock contention.
     */
    fun saveQueued(event: Event, accountPubkey: String) {
        val shouldFlushNow = synchronized(writeLock) {
            val existing = pendingWrites[event.id]
            val mergedOwner = accountPubkey.ifBlank { existing?.second ?: "" }
            pendingWrites[event.id] = event to mergedOwner
            pendingWrites.size >= Tunables.DB_WRITE_BATCH_MAX
        }
        if (shouldFlushNow) {
            flushImmediate.trySend(Unit)
        } else {
            flushDebounced.trySend(Unit)
        }
    }

    /** Persists all queued events in a single batched Room transaction. */
    suspend fun flushPendingWrites() {
        val batch = synchronized(writeLock) {
            if (pendingWrites.isEmpty()) return
            pendingWrites.values.toList().also { pendingWrites.clear() }
        }
        runCatching {
            val blankOwnerIds = batch.mapNotNull { (ev, owner) -> if (owner.isBlank()) ev.id else null }
            val preservedOwners = if (blankOwnerIds.isEmpty()) {
                emptyMap()
            } else {
                blankOwnerIds.chunked(SQLITE_IN_BATCH)
                    .flatMap { dao.getByIds(it) }
                    .associate { it.id to it.accountPubkey }
            }
            val entities = batch.map { (ev, owner) ->
                val resolvedOwner = owner.ifBlank { preservedOwners[ev.id] ?: "" }
                ev.toEntity(resolvedOwner)
            }
            dao.upsertAll(entities)
        }.onFailure {
            Timber.w(it, "Failed batch-saving %d feed event(s)", batch.size)
        }
    }

    suspend fun getById(id: String): Event? =
        dao.getById(id)?.let { runCatching { it.toEvent() }.getOrNull() }

    suspend fun getLatestByAuthorAndKind(pubkey: String, kind: Int): Event? =
        dao.getLatestByAuthorAndKind(pubkey.lowercase(), kind)?.let { runCatching { it.toEvent() }.getOrNull() }

    suspend fun getByIds(ids: List<String>): List<Event> {
        val unique = ids.distinct()
        if (unique.isEmpty()) return emptyList()
        // Chunk IN (:ids) queries to stay well under SQLite's 999 bind-variable limit on API 26-29.
        return unique.chunked(SQLITE_IN_BATCH)
            .flatMap { batch -> dao.getByIds(batch) }
            .mapNotNull { runCatching { it.toEvent() }.getOrNull() }
    }

    suspend fun getRecentAuthoredEvents(pubkey: String, limit: Int = 600): List<Event> =
        dao.getRecentByAuthor(pubkey, limit).mapNotNull { runCatching { it.toEvent() }.getOrNull() }

    /** Trims ancient cached events on startup so long-lived databases stay lean. */
    suspend fun pruneOld(maxKeep: Int = social.tbone.Tunables.MAX_CACHED_EVENTS): Int =
        dao.keepLatest(maxKeep)

    companion object {
        private const val SQLITE_IN_BATCH = 500
    }
}
