package social.tbone.lists

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayPool
import javax.inject.Inject
import javax.inject.Singleton

/** NIP-51 replaceable kind 10001 list, interoperable with Amethyst. */
@Singleton
class PinListRepository @Inject constructor(
    private val accounts: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val fetcher: RelayListFetcher,
    private val pool: RelayPool,
) {
    private val mutex = Mutex()
    private val _lists = MutableStateFlow<Map<String, Event>>(emptyMap())
    val lists = _lists.asStateFlow()

    fun consume(event: Event) {
        if (event.kind != EventKind.PIN_LIST) return
        _lists.update { lists ->
            val previous = lists[event.pubkey]
            if (previous != null && (previous.createdAt > event.createdAt ||
                (previous.createdAt == event.createdAt && previous.id <= event.id))) lists
            else lists + (event.pubkey to event)
        }
    }

    fun ids(event: Event?): List<String> = pinEventIds(event)

    /** Fetch before replacing so we never silently overwrite an unread list. */
    suspend fun toggle(target: Event): String = mutex.withLock {
        val account = accounts.activeAccount.first() ?: return@withLock "No active account"
        val signer = signerFactory.forActiveAccount() ?: return@withLock "No signer available"
        if (signer.pubkey != account.pubkey) return@withLock "Account changed; try again"
        val relays = account.effectiveRelays().map(RelayPool::normalize).toSet()
        val result = fetcher.fetchLatest(account.pubkey, EventKind.PIN_LIST, relays.toList())
        if (!result.reachedRelays) return@withLock "Couldn't read your pin list from relays; nothing changed"
        if (accounts.activeAccount.first()?.pubkey != account.pubkey) return@withLock "Account changed; try again"
        val remote = result.event
        val cached = _lists.value[account.pubkey]
        val old = listOfNotNull(remote, cached).maxWithOrNull(
            compareBy<Event> { it.createdAt }.thenByDescending { it.id }
        )
        val pinned = target.id in ids(old)
        val tags = old?.tags.orEmpty().filterNot { tag ->
            val parsed = social.tbone.nostr.Tag.fromJsonArray(tag)
            parsed.name == "e" && parsed.value() == target.id
        }.toMutableList()
        if (!pinned) tags.add(buildJsonArray { add("e"); add(target.id) })
        val now = System.currentTimeMillis() / 1000
        val createdAt = maxOf(now, (old?.createdAt ?: 0L) + 1)
        if (createdAt > now + 60) return@withLock "Existing pin list has a future timestamp; nothing changed"
        val signed = signer.signEvent(UnsignedEvent(pubkey = account.pubkey, createdAt = createdAt,
            kind = EventKind.PIN_LIST, tags = tags, content = old?.content.orEmpty())).getOrElse {
            return@withLock "Could not sign pin list"
        }
        if (pool.publishTo(signed, relays) == 0) return@withLock "No connected relays; pin list not sent"
        consume(signed)
        if (pinned) "Unpin sent to relays" else "Pin sent to relays"
    }
}

/** Public event IDs in a NIP-51 pin list; preserve order, ignore malformed tags. */
internal fun pinEventIds(event: Event?): List<String> = event?.parsedTags.orEmpty()
    .filter { it.name == "e" }.mapNotNull { it.value() }
    .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    .distinct().take(256)
