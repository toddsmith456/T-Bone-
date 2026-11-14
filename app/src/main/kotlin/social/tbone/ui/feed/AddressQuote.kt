package social.tbone.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.Tunables
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.relay.RelayHintPolicy
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AddressQuoteEntryPoint {
    fun relayPool(): RelayPool
    fun appSettings(): AppSettings
}

/**
 * Resolve NIP-19 `naddr1…` coordinates on already-connected relays first, and
 * if still missing, optionally check sanitized embedded `address.relayHints`
 * and/or built-in Fediverse bridge relays via scoped, read-only temporary leases
 * that are released immediately after the lookup.
 */
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
        val entryPoint = runCatching {
            EntryPointAccessors.fromApplication(context.applicationContext, AddressQuoteEntryPoint::class.java)
        }.getOrNull()
        val pool = entryPoint?.relayPool()
        val appSettings = entryPoint?.appSettings()
        if (pool == null) { finished = true; return@LaunchedEffect }

        val filter = listOf(
            Filter(
                authors = listOf(address.pubkey),
                kinds = listOf(address.kind),
                dTags = listOf(address.identifier),
                limit = 10,
            ),
        )

        suspend fun queryOnce(
            relays: Set<String>?,
            expectedRelays: Set<String>,
            timeoutMs: Long,
            onSubscribed: (() -> Unit)? = null,
        ): Set<String> {
            val subId = "naddr-" + UUID.randomUUID().toString().take(8)
            val answered = ConcurrentHashMap.newKeySet<String>()
            val done = CompletableDeferred<Unit>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                pool.messages.collect { envelope ->
                    when (val message = envelope.message) {
                        is RelayMessage.EventMessage -> {
                            val candidate = message.event
                            if (message.subscriptionId != subId || candidate.pubkey != address.pubkey ||
                                candidate.kind != address.kind ||
                                candidate.parsedTags.none { it.name == "d" && it.value() == address.identifier }
                            ) return@collect
                            if (withContext(Dispatchers.Default) { candidate.verify() } &&
                                (event == null || candidate.createdAt > event!!.createdAt ||
                                    (candidate.createdAt == event!!.createdAt && candidate.id < event!!.id))
                            ) {
                                event = candidate
                            }
                        }
                        is RelayMessage.EndOfStoredEvents -> {
                            if (message.subscriptionId != subId) return@collect
                            val normalized = RelayPool.normalize(envelope.relayUrl)
                            if (RelayHintPolicy.isMetadataOnlyRelay(normalized)) return@collect
                            answered.add(normalized)
                            if (expectedRelays.isNotEmpty() && answered.containsAll(expectedRelays)) {
                                done.complete(Unit)
                            }
                        }
                        is RelayMessage.Closed -> {
                            if (message.subscriptionId != subId) return@collect
                            val normalized = RelayPool.normalize(envelope.relayUrl)
                            if (RelayHintPolicy.isMetadataOnlyRelay(normalized)) return@collect
                            answered.add(normalized)
                            if (expectedRelays.isNotEmpty() && answered.containsAll(expectedRelays)) {
                                done.complete(Unit)
                            }
                        }
                        else -> Unit
                    }
                }
            }
            try {
                if (relays == null) {
                    pool.subscribe(filter, id = subId, label = "address-quote")
                } else {
                    pool.subscribeTo(filter, id = subId, label = "address-quote-fb", relays = relays)
                }
                onSubscribed?.invoke()
                withTimeoutOrNull(timeoutMs) { done.await() }
            } finally {
                collector.cancel()
                pool.unsubscribe(subId)
            }
            return answered.toSet()
        }

        try {
            // Stage 1: connected and connecting note-capable personal relays.
            val stage1Expected = pool.relayStatuses.value.entries
                .asSequence()
                .filter { (url, status) ->
                    (status == RelayStatus.CONNECTED || status == RelayStatus.CONNECTING) &&
                        RelayHintPolicy.isNoteCapableRelay(url)
                }
                .map { RelayPool.normalize(it.key) }
                .toSet()

            val torEnabled = appSettings?.torEnabled?.value ?: false
            val allowHints = appSettings?.followRelayHintsForMissingNotes?.value ?: true
            val allowBridges = appSettings?.fediverseBridgeRelaysEnabled?.value ?: true

            val hasFallbackOption = (allowHints && address.relayHints.isNotEmpty()) || allowBridges
            val stage1Answered = queryOnce(
                relays = null,
                expectedRelays = stage1Expected,
                timeoutMs = if (hasFallbackOption) 5_500L else 12_000L,
            )

            // Stage 2: scoped read-only fallback on sanitized hints + Fediverse bridges if still missing.
            if (event == null && (allowHints || allowBridges)) {
                // Only exclude relays that actually answered in Stage 1.
                val queriedLower = stage1Answered
                    .map { RelayPool.normalize(it).lowercase() }
                    .toSet()
                val fallbackRelays = LinkedHashSet<String>()
                if (allowHints) {
                    for (rawHint in address.relayHints) {
                        val clean = RelayHintPolicy.sanitizeHintUrl(rawHint, torEnabled) ?: continue
                        if (clean.lowercase() !in queriedLower) {
                            fallbackRelays += clean
                            if (fallbackRelays.size >= 5) break
                        }
                    }
                }
                if (allowBridges) {
                    for (bridge in Tunables.FEDIVERSE_BRIDGE_RELAYS) {
                        val clean = RelayHintPolicy.sanitizeHintUrl(bridge, torEnabled) ?: continue
                        if (clean.lowercase() !in queriedLower) {
                            fallbackRelays += clean
                            if (fallbackRelays.size >= 6) break
                        }
                    }
                }
                if (fallbackRelays.isNotEmpty()) {
                    val leased = mutableListOf<String>()
                    try {
                        queryOnce(
                            relays = fallbackRelays,
                            expectedRelays = fallbackRelays,
                            timeoutMs = if (torEnabled) 12_000L else 7_500L,
                            onSubscribed = {
                                fallbackRelays.forEach { url ->
                                    pool.acquireRelay(url)
                                    leased += url
                                }
                            },
                        )
                    } finally {
                        leased.forEach { pool.releaseRelay(it) }
                    }
                }
            }
        } finally {
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
