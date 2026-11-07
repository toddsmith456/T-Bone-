package social.tbone.ui.feed

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip19
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.ProfileContent
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.util.UUID

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AddressQuoteEntryPoint { fun relayPool(): RelayPool }

/** Resolve NIP-19 coordinates on the already-connected relays, without connecting to untrusted hints. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddressQuote(
    raw: String, address: Nip19.Address, profiles: Map<String, ProfileContent>,
    quotedEvents: Map<String, Event>, depth: Int, ancestors: Set<String>,
    onThreadClick: ((String) -> Unit)?, onProfileClick: ((String) -> Unit)?,
    onHashtagClick: ((String) -> Unit)?,
) {
    val context = LocalContext.current
    var event by remember(address) { mutableStateOf<Event?>(null) }
    var finished by remember(address) { mutableStateOf(false) }
    LaunchedEffect(address, depth) {
        if (depth >= 2) { finished = true; return@LaunchedEffect }
        finished = false
        val pool = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, AddressQuoteEntryPoint::class.java).relayPool()
        }.getOrNull()
        if (pool == null) { finished = true; return@LaunchedEffect }
        val id = UUID.randomUUID().toString().take(8)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            pool.messages.collect { envelope ->
                val message = envelope.message as? RelayMessage.EventMessage ?: return@collect
                val candidate = message.event
                if (message.subscriptionId != id || candidate.pubkey != address.pubkey ||
                    candidate.kind != address.kind || candidate.parsedTags.none { it.name == "d" && it.value() == address.identifier }) return@collect
                if (withContext(Dispatchers.Default) { candidate.verify() } &&
                    (event == null || candidate.createdAt > event!!.createdAt ||
                        (candidate.createdAt == event!!.createdAt && candidate.id < event!!.id))) event = candidate
            }
        }
        try {
            pool.subscribe(listOf(Filter(authors = listOf(address.pubkey), kinds = listOf(address.kind),
                dTags = listOf(address.identifier), limit = 10)), id = id, label = "address-quote")
            delay(15_000)
        } finally {
            collector.cancel()
            pool.unsubscribe(id)
            finished = true
        }
    }
    val resolved = event
    if (resolved != null && depth < 2 && resolved.id !in ancestors) {
        QuotedNoteCard(event = resolved, referenceUri = raw, profile = profiles[resolved.pubkey], profiles = profiles,
            quotedEvents = quotedEvents, onThreadClick = onThreadClick, onProfileClick = onProfileClick,
            onHashtagClick = onHashtagClick, nestingDepth = depth, quoteAncestors = ancestors)
    } else {
        val canOpen = resolved != null && onThreadClick != null
        Text(addressQuoteFallbackLabel(depth, resolved?.id, ancestors, finished, canOpen),
            style = BonyType.tag.copy(color = BonyColors.Accent),
            modifier = Modifier.combinedClickable(onClick = {
                if (canOpen) onThreadClick?.invoke(resolved!!.id) else copyNoteLink(context, raw)
            }, onLongClick = { copyNoteLink(context, raw) }).padding(vertical = 2.dp))
    }
}

/** Fallbacks never advertise an unavailable open action. */
internal fun addressQuoteFallbackLabel(
    depth: Int, resolvedId: String?, ancestors: Set<String>, finished: Boolean, canOpen: Boolean,
): String = when {
    depth >= 2 || (resolvedId != null && resolvedId in ancestors) ->
        if (canOpen) "open quoted note" else "nested quote hidden · tap to copy"
    finished -> "addressed note unavailable · tap to copy"
    else -> "loading quoted note… · tap to copy"
}
