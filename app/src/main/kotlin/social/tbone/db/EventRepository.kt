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
        listOf(EventKind.TEXT_NOTE, EventKind.REPOST), accountPubkey
    ).map { it.map(EventEntity::toEvent) }

    suspend fun getRecentFeedEvents(accountPubkey: String, limit: Int = 300): List<Event> =
        dao.getRecentByKinds(listOf(EventKind.TEXT_NOTE, EventKind.REPOST), accountPubkey, limit)
            .map(EventEntity::toEvent)

    suspend fun save(event: Event, accountPubkey: String) = dao.upsert(event.toEntity(accountPubkey))

    suspend fun getById(id: String): Event? = dao.getById(id)?.toEvent()

    suspend fun getByIds(ids: List<String>): List<Event> =
        dao.getByIds(ids).map(EventEntity::toEvent)
}
