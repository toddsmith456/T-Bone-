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
        listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL), accountPubkey
    ).map { it.map(EventEntity::toEvent) }

    suspend fun getRecentFeedEvents(accountPubkey: String, limit: Int = 300): List<Event> =
        dao.getRecentByKinds(
            listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
            accountPubkey,
            limit,
        ).map(EventEntity::toEvent)

    suspend fun save(event: Event, accountPubkey: String) {
        // Side-loads (threads, quotes and notifications) pass an empty account
        // because they are not feed-owned. Do not let that erase the account
        // association of an event already cached by a feed.
        val existing = dao.getById(event.id)
        val owner = accountPubkey.ifBlank { existing?.accountPubkey ?: "" }
        dao.upsert(event.toEntity(owner))
    }

    suspend fun getById(id: String): Event? = dao.getById(id)?.toEvent()

    suspend fun getByIds(ids: List<String>): List<Event> =
        dao.getByIds(ids).map(EventEntity::toEvent)

    suspend fun getRecentAuthoredEvents(pubkey: String, limit: Int = 600): List<Event> =
        dao.getRecentByAuthor(pubkey, limit).map(EventEntity::toEvent)
}
