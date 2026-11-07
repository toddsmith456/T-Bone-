package social.tbone.ui.feed

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayMessage

/** Collect before REQ, then retain the newest valid response during a bounded grace window. */
internal suspend fun fetchClockFollowEvent(
    messages: Flow<PoolMessage>, id: String, author: String,
    subscribe: () -> Unit, unsubscribe: () -> Unit,
    timeoutMs: Long = 8_000, graceMs: Long = 2_000,
    verify: (Event) -> Boolean = { it.verify() },
): Event? = coroutineScope {
    val best = MutableStateFlow<Event?>(null)
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        messages.collect { (_, msg) ->
            val event = (msg as? RelayMessage.EventMessage)?.takeIf { it.subscriptionId == id }?.event ?: return@collect
            if (event.pubkey != author || event.kind != EventKind.FOLLOW_LIST || !verify(event)) return@collect
            val old = best.value
            if (old == null || event.createdAt > old.createdAt ||
                (event.createdAt == old.createdAt && event.id < old.id)) best.value = event
        }
    }
    try {
        withTimeoutOrNull(timeoutMs) {
            subscribe()
            best.first { it != null }
            delay(graceMs)
        }
    } finally {
        collector.cancel()
        unsubscribe()
    }
    best.value
}
