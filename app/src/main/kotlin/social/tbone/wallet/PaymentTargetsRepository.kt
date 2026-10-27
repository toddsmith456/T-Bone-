package social.tbone.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.EventRepository
import social.tbone.nostr.Event
import social.tbone.nostr.Filter
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import java.util.LinkedHashMap
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** A verified latest-known kind-10133 event and the scope of its relay check. */
data class MoneroTargetLookup(
    val event: Event?,
    val fullySynced: Boolean,
    val usedLocalCache: Boolean,
    val queriedRelays: Int,
    val respondingRelays: Int,
) {
    val moneroAddress: String? get() = NipA3PaymentTargets.moneroAddress(event)
    val hasMoneroTag: Boolean get() = NipA3PaymentTargets.hasMoneroTag(event)
}

/** Lightning counterpart — mirrors [MoneroTargetLookup] exactly. */
data class LightningTargetLookup(
    val event: Event?,
    val fullySynced: Boolean,
    val usedLocalCache: Boolean,
    val queriedRelays: Int,
    val respondingRelays: Int,
) {
    val lightningAddress: String? get() = NipA3PaymentTargets.lightningAddress(event)
    val hasLightningTag: Boolean get() = NipA3PaymentTargets.hasLightningTag(event)
}

data class PaymentTargetPublishReceipt(
    val acceptedRelays: Int,
    val attemptedRelays: Int,
    val acknowledgedRelays: Int,
)

/**
 * Fetches and publishes NIP-A3 kind-10133 payment targets. It only handles a
 * public receive address; all Monero key custody and payment execution remain
 * in the user's wallet app.
 */
@Singleton
class PaymentTargetsRepository @Inject constructor(
    private val relayPool: RelayPool,
    private val eventRepository: EventRepository,
    private val signerFactory: NostrSignerFactory,
    private val accountRepository: AccountRepository,
) {
    private data class CachedLookup(val value: MoneroTargetLookup, val cachedAtMs: Long)
    private data class RelayLookup(
        val events: List<Event>,
        val complete: Boolean,
        val queriedRelayCount: Int,
        val respondingRelayCount: Int,
    )

    private val lookupMutex = Mutex()
    private val cacheMutex = Any()
    private val lookupCache = LinkedHashMap<String, CachedLookup>(16, 0.75f, true)

    /**
     * Queries kind 10133 on every currently connected relay before using a
     * cached result. A short memory cache avoids repeating that request for
     * every note tap. If the network is unavailable, the returned event is the
     * latest verified local copy and `fullySynced` is false so the UI can warn.
     */
    suspend fun lookupMoneroTarget(
        pubkey: String,
        forceRefresh: Boolean = false,
    ): MoneroTargetLookup = withContext(Dispatchers.IO) {
        require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
        val authorPubkey = pubkey.lowercase()
        val now = System.currentTimeMillis()
        if (!forceRefresh) cached(authorPubkey, now)?.let { return@withContext it }

        lookupMutex.withLock {
            val insideNow = System.currentTimeMillis()
            if (!forceRefresh) cached(authorPubkey, insideNow)?.let { return@withLock it }

            val local = eventRepository.getLatestByAuthorAndKind(authorPubkey, NipA3PaymentTargets.KIND)
                ?.takeIf { it.pubkey.equals(authorPubkey, ignoreCase = true) && it.verify() }
            val network = fetchLatestFromConnectedRelays(authorPubkey)
            val newestRelayEvent = network.events.maxWithOrNull(EVENT_ORDER)
            // Prefer whichever verified replaceable event is newest, while
            // remembering if relays did not confirm a locally cached version.
            // That lets target updates preserve unknown tags without hiding the
            // fact that a hand-off address came from local cache.
            val newest = listOfNotNull(local, newestRelayEvent).maxWithOrNull(EVENT_ORDER)

            if (newestRelayEvent != null &&
                (local == null || EVENT_ORDER.compare(newestRelayEvent, local) > 0)
            ) {
                eventRepository.save(newestRelayEvent, "")
            }

            val usedLocalCache = newest != null && newestRelayEvent?.id != newest.id
            val value = MoneroTargetLookup(
                event = newest,
                fullySynced = network.complete && !usedLocalCache,
                usedLocalCache = usedLocalCache,
                queriedRelays = network.queriedRelayCount,
                respondingRelays = network.respondingRelayCount,
            )
            if (value.fullySynced) cache(authorPubkey, value, System.currentTimeMillis())
            value
        }
    }

    /**
     * Updates only the user's Monero/XMR tags, preserving all other NIP-A3
     * targets and unknown tags. Publication is not reported as complete until
     * at least one connected relay returns an accepted `OK` for the event.
     */
    suspend fun publishOwnMoneroAddress(
        pubkey: String,
        rawAddress: String?,
    ): Result<PaymentTargetPublishReceipt> {
        return try {
            val receipt = withContext(Dispatchers.IO) {
                require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
                val authorPubkey = pubkey.lowercase()
                val address = rawAddress?.takeIf { it.isNotBlank() }?.let(MoneroAddressPolicy::normalize)
                val latest = lookupMoneroTarget(authorPubkey, forceRefresh = true)
                require(latest.fullySynced) {
                    "Could not confirm the latest payment-target event from connected relays. Try again when relays are online; nothing was changed."
                }

                val account = accountRepository.activeAccount.first()
                    ?: error("No active Nostr account")
                require(account.pubkey.equals(authorPubkey, ignoreCase = true)) {
                    "The active account changed. Reload the wallet tool and try again."
                }
                val signer = signerFactory.forActiveAccount()
                    ?: error("No Nostr signer is available for this account")
                require(signer.pubkey.equals(authorPubkey, ignoreCase = true)) {
                    "The active signer does not match this account"
                }

                val createdAt = maxOf(
                    System.currentTimeMillis() / 1000,
                    (latest.event?.createdAt ?: 0L) + 1L,
                )
                val unsigned = NipA3PaymentTargets.updateMoneroAddress(
                    pubkey = authorPubkey,
                    existing = latest.event,
                    address = address,
                    createdAt = createdAt,
                )
                val signed = signer.signEvent(unsigned).getOrElse { throw it }
                require(
                    signed.kind == NipA3PaymentTargets.KIND &&
                        signed.pubkey.equals(authorPubkey, ignoreCase = true) &&
                        signed.verify()
                ) { "The signed payment-target event could not be verified" }

                val connectedRelays = relayPool.relayStatuses.value
                    .filterValues { it == social.tbone.nostr.relay.RelayStatus.CONNECTED }
                    .keys
                require(connectedRelays.isNotEmpty()) { "No connected Nostr relays are available" }
                val published = publishAndAwaitAcceptance(signed, connectedRelays)
                eventRepository.save(signed, "")
                cache(
                    authorPubkey,
                    MoneroTargetLookup(
                        event = signed,
                        fullySynced = true,
                        usedLocalCache = false,
                        queriedRelays = published.attemptedRelays,
                        respondingRelays = published.acknowledgedRelays,
                    ),
                    System.currentTimeMillis(),
                )
                published
            }
            Result.success(receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun fetchLatestFromConnectedRelays(pubkey: String): RelayLookup = coroutineScope {
        val connected = relayPool.relayStatuses.value
            .filterValues { it == social.tbone.nostr.relay.RelayStatus.CONNECTED }
            .keys
        if (connected.isEmpty()) return@coroutineScope RelayLookup(emptyList(), false, 0, 0)

        val subscriptionId = "payto-${UUID.randomUUID().toString().take(10)}"
        val events = LinkedHashMap<String, Event>()
        val ended = LinkedHashSet<String>()
        val allRelaysEnded = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.messages.collect { message ->
                if (message.message is RelayMessage.EventMessage &&
                    message.message.subscriptionId == subscriptionId
                ) {
                    val event = message.message.event
                    if (event.kind == NipA3PaymentTargets.KIND &&
                        event.pubkey.equals(pubkey, ignoreCase = true) &&
                        runCatching { event.verify() }.getOrDefault(false)
                    ) {
                        events[event.id] = event
                    }
                } else if (message.message is RelayMessage.EndOfStoredEvents &&
                    message.message.subscriptionId == subscriptionId
                ) {
                    ended += RelayPool.normalize(message.relayUrl)
                    if (ended.containsAll(connected.map { RelayPool.normalize(it) }.toSet())) {
                        allRelaysEnded.complete(Unit)
                    }
                }
            }
        }

        try {
            relayPool.subscribeTo(
                filters = listOf(
                    Filter(
                        authors = listOf(pubkey),
                        kinds = listOf(NipA3PaymentTargets.KIND),
                        limit = 1,
                    ),
                ),
                id = subscriptionId,
                label = "nip-a3-payment-targets",
                relays = connected,
            )
            val complete = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                allRelaysEnded.await()
                true
            } ?: false
            RelayLookup(
                events = events.values.toList(),
                complete = complete,
                queriedRelayCount = connected.size,
                respondingRelayCount = ended.size,
            )
        } finally {
            relayPool.unsubscribe(subscriptionId)
            collector.cancelAndJoin()
        }
    }

    private suspend fun publishAndAwaitAcceptance(
        event: Event,
        relays: Set<String>,
    ): PaymentTargetPublishReceipt = coroutineScope {
        val acknowledgements = LinkedHashMap<String, RelayMessage.Ok>()
        val acknowledgementMutex = Any()
        val allAcknowledged = CompletableDeferred<Unit>()
        var expectedAcks = Int.MAX_VALUE
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            relayPool.messages.collect { message: PoolMessage ->
                val ok = message.message as? RelayMessage.Ok ?: return@collect
                if (ok.eventId != event.id) return@collect
                synchronized(acknowledgementMutex) {
                    acknowledgements[RelayPool.normalize(message.relayUrl)] = ok
                    if (acknowledgements.size >= expectedAcks) allAcknowledged.complete(Unit)
                }
            }
        }

        try {
            val sent = relayPool.publishTo(event, relays)
            require(sent > 0) { "The signed event could not be sent to a connected relay" }
            expectedAcks = sent
            synchronized(acknowledgementMutex) {
                if (acknowledgements.size >= expectedAcks) allAcknowledged.complete(Unit)
            }
            withTimeoutOrNull(PUBLISH_ACK_TIMEOUT_MS) { allAcknowledged.await() }
            val ackSnapshot = synchronized(acknowledgementMutex) { acknowledgements.values.toList() }
            val accepted = ackSnapshot.count { it.accepted }
            if (accepted == 0) {
                val rejected = ackSnapshot.firstOrNull { !it.accepted }?.message?.takeIf { it.isNotBlank() }
                error(
                    rejected?.let { "Relays rejected the payment-target update: $it" }
                        ?: "The payment-target update was sent but no relay confirmed it. Check your profile before retrying."
                )
            }
            PaymentTargetPublishReceipt(
                acceptedRelays = accepted,
                attemptedRelays = sent,
                acknowledgedRelays = ackSnapshot.size,
            )
        } finally {
            collector.cancelAndJoin()
        }
    }

    // ── Lightning (mirrors Monero) ────────────────────────────────────────────

    private data class CachedLightningLookup(val value: LightningTargetLookup, val cachedAtMs: Long)
    private val lightningLookupCache = LinkedHashMap<String, CachedLightningLookup>(16, 0.75f, true)

    suspend fun lookupLightningTarget(
        pubkey: String,
        forceRefresh: Boolean = false,
    ): LightningTargetLookup = withContext(Dispatchers.IO) {
        require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
        val authorPubkey = pubkey.lowercase()
        val now = System.currentTimeMillis()
        if (!forceRefresh) lightningCached(authorPubkey, now)?.let { return@withContext it }

        lookupMutex.withLock {
            val insideNow = System.currentTimeMillis()
            if (!forceRefresh) lightningCached(authorPubkey, insideNow)?.let { return@withLock it }

            val local = eventRepository.getLatestByAuthorAndKind(authorPubkey, NipA3PaymentTargets.KIND)
                ?.takeIf { it.pubkey.equals(authorPubkey, ignoreCase = true) && it.verify() }
            val network = fetchLatestFromConnectedRelays(authorPubkey)
            val newestRelayEvent = network.events.maxWithOrNull(EVENT_ORDER)
            val newest = listOfNotNull(local, newestRelayEvent).maxWithOrNull(EVENT_ORDER)

            if (newestRelayEvent != null &&
                (local == null || EVENT_ORDER.compare(newestRelayEvent, local) > 0)
            ) {
                eventRepository.save(newestRelayEvent, "")
            }

            val usedLocalCache = newest != null && newestRelayEvent?.id != newest.id
            val value = LightningTargetLookup(
                event = newest,
                fullySynced = network.complete && !usedLocalCache,
                usedLocalCache = usedLocalCache,
                queriedRelays = network.queriedRelayCount,
                respondingRelays = network.respondingRelayCount,
            )
            if (value.fullySynced) lightningCache(authorPubkey, value, System.currentTimeMillis())
            value
        }
    }

    suspend fun publishOwnLightningAddress(
        pubkey: String,
        rawAddress: String?,
    ): Result<PaymentTargetPublishReceipt> {
        return try {
            val receipt = withContext(Dispatchers.IO) {
                require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
                val authorPubkey = pubkey.lowercase()
                val address = rawAddress?.takeIf { it.isNotBlank() }?.let(LightningAddressPolicy::normalize)
                val latest = lookupLightningTarget(authorPubkey, forceRefresh = true)
                require(latest.fullySynced) {
                    "Could not confirm the latest payment-target event from connected relays. Try again when relays are online; nothing was changed."
                }

                val account = accountRepository.activeAccount.first()
                    ?: error("No active Nostr account")
                require(account.pubkey.equals(authorPubkey, ignoreCase = true)) {
                    "The active account changed. Reload the wallet tool and try again."
                }
                val signer = signerFactory.forActiveAccount()
                    ?: error("No Nostr signer is available for this account")
                require(signer.pubkey.equals(authorPubkey, ignoreCase = true)) {
                    "The active signer does not match this account"
                }

                val createdAt = maxOf(
                    System.currentTimeMillis() / 1000,
                    (latest.event?.createdAt ?: 0L) + 1L,
                )
                val unsigned = NipA3PaymentTargets.updateLightningAddress(
                    pubkey = authorPubkey,
                    existing = latest.event,
                    address = address,
                    createdAt = createdAt,
                )
                val signed = signer.signEvent(unsigned).getOrElse { throw it }
                require(
                    signed.kind == NipA3PaymentTargets.KIND &&
                        signed.pubkey.equals(authorPubkey, ignoreCase = true) &&
                        signed.verify()
                ) { "The signed payment-target event could not be verified" }

                val connectedRelays = relayPool.relayStatuses.value
                    .filterValues { it == social.tbone.nostr.relay.RelayStatus.CONNECTED }
                    .keys
                require(connectedRelays.isNotEmpty()) { "No connected Nostr relays are available" }
                val published = publishAndAwaitAcceptance(signed, connectedRelays)
                eventRepository.save(signed, "")
                lightningCache(
                    authorPubkey,
                    LightningTargetLookup(
                        event = signed,
                        fullySynced = true,
                        usedLocalCache = false,
                        queriedRelays = published.attemptedRelays,
                        respondingRelays = published.acknowledgedRelays,
                    ),
                    System.currentTimeMillis(),
                )
                published
            }
            Result.success(receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun cached(pubkey: String, now: Long): MoneroTargetLookup? = synchronized(cacheMutex) {
        val entry = lookupCache[pubkey] ?: return@synchronized null
        if (now - entry.cachedAtMs > CACHE_TTL_MS) {
            lookupCache.remove(pubkey)
            null
        } else {
            entry.value
        }
    }

    private fun cache(pubkey: String, value: MoneroTargetLookup, now: Long) = synchronized(cacheMutex) {
        lookupCache[pubkey] = CachedLookup(value, now)
        while (lookupCache.size > MAX_CACHE_ENTRIES) {
            val oldestKey = lookupCache.entries.iterator().next().key
            lookupCache.remove(oldestKey)
        }
    }

    private fun lightningCached(pubkey: String, now: Long): LightningTargetLookup? = synchronized(cacheMutex) {
        val entry = lightningLookupCache[pubkey] ?: return@synchronized null
        if (now - entry.cachedAtMs > CACHE_TTL_MS) {
            lightningLookupCache.remove(pubkey)
            null
        } else {
            entry.value
        }
    }

    private fun lightningCache(pubkey: String, value: LightningTargetLookup, now: Long) = synchronized(cacheMutex) {
        lightningLookupCache[pubkey] = CachedLightningLookup(value, now)
        while (lightningLookupCache.size > MAX_CACHE_ENTRIES) {
            val oldestKey = lightningLookupCache.entries.iterator().next().key
            lightningLookupCache.remove(oldestKey)
        }
    }

    companion object {
        private const val LOOKUP_TIMEOUT_MS = 5_000L
        private const val PUBLISH_ACK_TIMEOUT_MS = 5_000L
        private const val CACHE_TTL_MS = 5 * 60 * 1_000L
        private const val MAX_CACHE_ENTRIES = 500
        private val EVENT_ORDER = compareBy<Event> { it.createdAt }.thenBy { it.id }
    }
}
