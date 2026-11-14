package social.tbone.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventRepository @Inject constructor(private val dao: EventDao) {

    fun feedEvents(accountPubkey: String): Flow<List<Event>> = dao.observeByKinds(
        listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.GENERIC_REPOST, EventKind.POLL), accountPubkey
    ).map { list -> list.mapNotNull { runCatching { it.toEvent() }.getOrNull() } }

    suspend fun getRecentFeedEvents(accountPubkey: String, limit: Int = 300): List<Event> =
        dao.getRecentByKinds(
            listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.GENERIC_REPOST, EventKind.POLL),
            accountPubkey,
            limit,
        ).mapNotNull { runCatching { it.toEvent() }.getOrNull() }

    suspend fun save(event: Event, accountPubkey: String) {
        // Side-loads (threads, quotes and notifications) pass an empty account
        // because they are not feed-owned. Do not let that erase the account
        // association of an event already cached by a feed.
        val existing = dao.getById(event.id)
        val owner = accountPubkey.ifBlank { existing?.accountPubkey ?: "" }
        dao.upsert(event.toEntity(owner))
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

    companion object {
        private const val SQLITE_IN_BATCH = 500
    }
}
