package social.tbone.tokens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.db.TokenCacheDao
import social.tbone.db.TokenLedgerStateEntity
import social.tbone.db.VerifiedTokenEntity
import social.tbone.nostr.DailyTokens
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.Nip13
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class LeaderboardScope(val label: String) {
    FOLLOWS("FOLLOWS"),
    GLOBAL("GLOBAL"),
}

enum class OfferStatus {
    CLAIMABLE,
    CLAIMING,
    CLAIMED,
    EXPIRED,
}

data class IncomingSteakOffer(
    val offer: DailyTokens.ParsedTokenOffer,
    val claimed: Boolean,
    val claimId: String? = null,
    val claimedAt: Long? = null,
    val claiming: Boolean = false,
) {
    fun statusAt(nowSeconds: Long): OfferStatus = when {
        claimed -> OfferStatus.CLAIMED
        claiming -> OfferStatus.CLAIMING
        nowSeconds > offer.expiresAt -> OfferStatus.EXPIRED
        else -> OfferStatus.CLAIMABLE
    }

    fun remainingSecondsAt(nowSeconds: Long): Long =
        (offer.expiresAt - nowSeconds).coerceAtLeast(0L)
}

data class ProfileSteakSummary(
    val pubkey: String,
    val receipts: List<DailyTokens.VerifiedTokenReceipt> = emptyList(),
    val isLoading: Boolean = false,
    val lastSyncedAt: Long = 0L,
) {
    val count: Int get() = receipts.size
}

data class SteakLeaderboardEntry(
    val pubkey: String,
    val verifiedCount: Int,
    val latestClaimedAt: Long,
    val rank: Int,
)

/**
 * Coordinates NIP-XX Daily Claimable Tokens (`steak`):
 *  - Sending 16-bit NIP-13 PoW Token Offers (`kind: 8701`) with rolling 24h cooldown
 *  - Claiming incoming Offers within 24h (`kind: 8702`) + updating Profile Token Ledger (`kind: 38701`)
 *  - Cryptographically verifying profile ledgers & building Follows / Global leaderboards
 *  - Caching verified receipts in Room so counts load instantly on cold start
 */
@Singleton
class SteakRepository @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val signerFactory: NostrSignerFactory,
    private val profileRepository: ProfileRepository,
    private val tokenCacheDao: TokenCacheDao,
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Uncaught exception in SteakRepository")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val sendMutex = Mutex()
    private val claimMutex = Mutex()

    @Volatile private var activePubkey: String? = null

    /** Relay list per account, refreshed when the account changes (see switchAccount). */
    private val cachedAccountRelays = ConcurrentHashMap<String, List<String>>()

    /** Guards [liveSubId] and [liveCollectorJob]; surface counting is in [surfaces]. */
    private val liveLock = Any()
    private var liveSubId: String? = null
    private var liveCollectorJob: Job? = null
    /** How many steak surfaces are currently on screen. */
    private val surfaces = SurfaceRefCounter()

    /** Offers observed sent by the active user (drives rolling 24h cooldown). */
    private val mySentOffers = ConcurrentHashMap<String, DailyTokens.ParsedTokenOffer>()

    /** Incoming steak offers addressed to the active user (offerId -> parsed offer). */
    private val myIncomingOffers = ConcurrentHashMap<String, DailyTokens.ParsedTokenOffer>()

    /** Claims authored by the active user (offerId -> parsed claim). */
    private val myClaimsByOfferId = ConcurrentHashMap<String, DailyTokens.ParsedTokenClaim>()

    /** Latest parsed primary ledger observed per pubkey (in-memory hot mirror). */
    private val observedLedgers = ConcurrentHashMap<String, DailyTokens.ParsedTokenLedger>()

    private val _cooldownUntilByRecipient = MutableStateFlow<Map<String, Long>>(emptyMap())
    /**
     * Active rolling 24-hour cooldown expiry timestamps (epoch seconds) keyed by
     * `recipientPubkey`. Recipients not in this map (or with value <= now) can
     * receive a steak right now.
     */
    val cooldownUntilByRecipient: StateFlow<Map<String, Long>> = _cooldownUntilByRecipient.asStateFlow()

    private val _incomingOffers = MutableStateFlow<List<IncomingSteakOffer>>(emptyList())
    val incomingOffers: StateFlow<List<IncomingSteakOffer>> = _incomingOffers.asStateFlow()

    private val _claimedOfferIds = MutableStateFlow<Set<String>>(emptySet())
    val claimedOfferIds: StateFlow<Set<String>> = _claimedOfferIds.asStateFlow()

    private val _claimingOfferIds = MutableStateFlow<Set<String>>(emptySet())
    val claimingOfferIds: StateFlow<Set<String>> = _claimingOfferIds.asStateFlow()

    private val _sendingToPubkey = MutableStateFlow<String?>(null)
    val sendingToPubkey: StateFlow<String?> = _sendingToPubkey.asStateFlow()

    private val _profileSummaries = MutableStateFlow<Map<String, ProfileSteakSummary>>(emptyMap())
    val profileSummaries: StateFlow<Map<String, ProfileSteakSummary>> = _profileSummaries.asStateFlow()

    private val _followsLeaderboard = MutableStateFlow<List<SteakLeaderboardEntry>>(emptyList())
    val followsLeaderboard: StateFlow<List<SteakLeaderboardEntry>> = _followsLeaderboard.asStateFlow()

    private val _globalLeaderboard = MutableStateFlow<List<SteakLeaderboardEntry>>(emptyList())
    val globalLeaderboard: StateFlow<List<SteakLeaderboardEntry>> = _globalLeaderboard.asStateFlow()

    private val _leaderboardLoading = MutableStateFlow(false)
    val leaderboardLoading: StateFlow<Boolean> = _leaderboardLoading.asStateFlow()

    private val _unlistedClaimCount = MutableStateFlow(0)
    /** Claims published as kind:8702 by the active user that aren't yet listed in their 38701 ledger. */
    val unlistedClaimCount: StateFlow<Int> = _unlistedClaimCount.asStateFlow()

    private val activeProfileRefreshJobs = ConcurrentHashMap<String, Job>()
    private var leaderboardJob: Job? = null

    init {
        scope.launch {
            accountRepository.activeAccount
                .distinctUntilChangedBy { it?.pubkey }
                .collect { account ->
                    switchAccount(account)
                }
        }
    }

    // ── Account lifecycle ─────────────────────────────────────────────────────

    private suspend fun switchAccount(account: Account?) {
        val pubkey = account?.pubkey
        synchronized(liveLock) {
            liveSubId?.let { pool.unsubscribe(it) }
            liveSubId = null
        }
        if (pubkey != null && account != null) {
            cachedAccountRelays[pubkey] = account.effectiveRelays()
        }
        activePubkey = pubkey
        mySentOffers.clear()
        myIncomingOffers.clear()
        myClaimsByOfferId.clear()
        _cooldownUntilByRecipient.value = emptyMap()
        _incomingOffers.value = emptyList()
        _claimedOfferIds.value = emptySet()
        _claimingOfferIds.value = emptySet()
        _sendingToPubkey.value = null
        _unlistedClaimCount.value = 0

        if (pubkey == null) return

        // Pre-seed own claimed offer IDs from Room verified receipts so notifications
        // immediately show "CLAIMED" on cold start before relays answer.
        val cachedMine = runCatching {
            tokenCacheDao.getVerifiedForRecipient(DailyTokens.STEAK_SLUG, pubkey)
        }.getOrDefault(emptyList())
        if (cachedMine.isNotEmpty()) {
            _claimedOfferIds.value = cachedMine.map { it.offerId }.toSet()
            _profileSummaries.update { map ->
                map + (pubkey to ProfileSteakSummary(
                    pubkey = pubkey,
                    receipts = cachedMine.map { it.toReceipt() },
                    isLoading = false,
                ))
            }
        }

        // A surface opened before the account was known still expects live data.
        if (surfaces.isHeld()) startLiveUpdates()
        loadProfileSteaks(pubkey)
        // Badge state for the Toolbox without keeping a live subscription open.
        refreshPendingBadge()
    }

    // ── Lazy live updates ─────────────────────────────────────────────────────

    /**
     * Steak data is only fetched while a steak surface is on screen.
     *
     * This repository used to open an unscoped four-filter subscription in
     * `init`, i.e. on every cold start, before the user had opened anything —
     * four REQs times every connected relay, re-sent on every reconnect, and one
     * more collector competing for the shared message flow during the window
     * where the feed is trying to fill. Nothing on the Home feed needs it: the
     * feed never reads steak data.
     *
     * The live subscription now starts on the first [acquireLiveUpdates] and stops
     * on the last [releaseLiveUpdates], so it costs nothing unless someone is
     * actually looking at steaks.
     */
    fun acquireLiveUpdates() {
        if (surfaces.acquire()) startLiveUpdates()
    }

    fun releaseLiveUpdates() {
        if (surfaces.release()) scope.launch { stopLiveUpdates() }
    }

    /** True while a steak surface holds the live subscription. */
    fun isLiveActive(): Boolean = synchronized(liveLock) { liveSubId != null }

    private fun startLiveUpdates() {
        val pubkey = activePubkey ?: return
        scope.launch {
            synchronized(liveLock) {
                if (liveSubId != null) return@launch
            }
            ensureLiveCollector()
            subscribeLive(pubkey)
        }
    }

    private suspend fun stopLiveUpdates() {
        val id = synchronized(liveLock) {
            val current = liveSubId
            liveSubId = null
            current
        }
        id?.let { pool.unsubscribe(it) }
    }

    /** One long-lived collector, created the first time a surface needs it. */
    private fun ensureLiveCollector() {
        synchronized(liveLock) {
            if (liveCollectorJob?.isActive == true) return
            liveCollectorJob = scope.launch {
                pool.messages.collect { poolMessage ->
                    val msg = poolMessage.message
                    val id = synchronized(liveLock) { liveSubId }
                    if (msg is RelayMessage.EventMessage && msg.subscriptionId == id) {
                        handleLiveEvent(msg.event)
                    }
                }
            }
        }
    }

    private fun subscribeLive(pubkey: String) {
        val now = nowEpochSeconds()
        val created = pool.subscribe(
            filters = listOf(
                // My outgoing steak offers in the last 24h (per-recipient cooldown sync).
                Filter(
                    kinds = listOf(EventKind.TOKEN_OFFER),
                    authors = listOf(pubkey),
                    tTags = listOf(DailyTokens.STEAK_SLUG),
                    since = now - DailyTokens.COOLDOWN_SECONDS,
                    limit = 200,
                ),
                // Incoming steak offers addressed to me in the last 24h.
                Filter(
                    kinds = listOf(EventKind.TOKEN_OFFER),
                    pTags = listOf(pubkey),
                    tTags = listOf(DailyTokens.STEAK_SLUG),
                    since = now - DailyTokens.CLAIM_WINDOW_SECONDS,
                    limit = 50,
                ),
                // My recent steak claims so we know which offers are already accepted.
                Filter(
                    kinds = listOf(EventKind.TOKEN_CLAIM),
                    authors = listOf(pubkey),
                    tTags = listOf(DailyTokens.STEAK_SLUG),
                    since = now - DailyTokens.CLAIM_WINDOW_SECONDS * 2,
                    limit = 100,
                ),
                // My own profile ledger.
                Filter(
                    kinds = listOf(EventKind.TOKEN_LEDGER),
                    authors = listOf(pubkey),
                    dTags = listOf(DailyTokens.STEAK_SLUG),
                    limit = 2,
                ),
            ),
            label = "steaks-live",
            // Only relays that can plausibly hold this account's own steak events:
            // its write relays plus the relays other users' offers would arrive on.
            // An unscoped sub sent the same four REQs to every connected relay and
            // re-sent them on each reconnect.
            relays = steakRelays(pubkey),
        )
        synchronized(liveLock) { liveSubId = created }
    }

    /**
     * Relay set for the live steak subscription.
     *
     * The account's own write relays are where its offers, claims and ledger land.
     * Offers from other people are addressed to `p = <me>`, which the sender
     * publishes to whichever relays it considers ours, so the configured personal
     * relays are included as well. Capped so a large relay list cannot fan the
     * subscription back out into the problem this is fixing.
     */
    private fun steakRelays(pubkey: String): Set<String> {
        val relays = LinkedHashSet<String>()
        // Where this account publishes, so its own offers/claims/ledger are visible.
        relays.addAll(cachedAccountRelays[pubkey].orEmpty())
        relays.addAll(pool.relayUrls())
        return relays.map(RelayPool::normalize).filter(RelayPool::isValidRelayUrl)
            .take(MAX_LIVE_RELAYS).toSet()
    }

    /**
     * Cheap badge refresh: one REQ for claimable offers addressed to the active
     * account, with no live subscription.
     *
     * The Toolbox shows a dot when a steak is waiting. That used to be free
     * because a live subscription always existed; now that the live subscription
     * only runs while a steak surface is open, the badge is refreshed on demand —
     * once at startup and whenever the Toolbox is opened. One short request per
     * launch is a fair price for not keeping four filters on every relay for the
     * whole session.
     */
    fun refreshPendingBadge() {
        val me = activePubkey ?: return
        scope.launch {
            runCatching {
                val offers = queryOnce(
                    filters = listOf(
                        Filter(
                            kinds = listOf(EventKind.TOKEN_OFFER),
                            pTags = listOf(me),
                            tTags = listOf(DailyTokens.STEAK_SLUG),
                            since = nowEpochSeconds() - DailyTokens.CLAIM_WINDOW_SECONDS,
                            limit = 25,
                        ),
                    ),
                    label = "steak-badge",
                    timeoutMs = 3_000L,
                )
                offers.forEach { ev ->
                    val parsed = DailyTokens.parseOffer(ev, nowEpochSeconds(), requireSig = true)
                        ?: return@forEach
                    if (parsed.slug == DailyTokens.STEAK_SLUG && parsed.recipientPubkey == me) {
                        myIncomingOffers[parsed.offerId] = parsed
                    }
                }
                rebuildIncomingOffers()
            }.onFailure { Timber.w(it, "refreshPendingBadge failed") }
        }
    }

    /** Called by NotificationsRepository when a steak offer arrives on its subscription. */
    fun onIncomingOfferObserved(event: Event) {
        val me = activePubkey ?: return
        val parsed = DailyTokens.parseOffer(event, nowEpochSeconds(), requireSig = true) ?: return
        if (parsed.slug != DailyTokens.STEAK_SLUG || parsed.recipientPubkey != me) return
        if (myIncomingOffers.putIfAbsent(parsed.offerId, parsed) == null) {
            profileRepository.requestProfiles(listOf(parsed.senderPubkey))
            rebuildIncomingOffers()
        }
    }

    private fun handleLiveEvent(event: Event) {
        val me = activePubkey ?: return
        val now = nowEpochSeconds()
        when (event.kind) {
            EventKind.TOKEN_OFFER -> {
                val parsed = DailyTokens.parseOffer(event, now, requireSig = true) ?: return
                if (parsed.slug != DailyTokens.STEAK_SLUG) return
                if (parsed.senderPubkey == me) {
                    mySentOffers[parsed.offerId] = parsed
                    recomputeCooldown(me)
                } else if (parsed.recipientPubkey == me) {
                    myIncomingOffers[parsed.offerId] = parsed
                    profileRepository.requestProfiles(listOf(parsed.senderPubkey))
                    rebuildIncomingOffers()
                }
            }
            EventKind.TOKEN_CLAIM -> {
                val parsed = DailyTokens.parseClaim(event, requireSig = true) ?: return
                if (parsed.slug != DailyTokens.STEAK_SLUG || parsed.recipientPubkey != me) return
                val existing = myClaimsByOfferId[parsed.offerId]
                if (existing == null ||
                    parsed.claimedAt < existing.claimedAt ||
                    (parsed.claimedAt == existing.claimedAt && parsed.claimId < existing.claimId)
                ) {
                    myClaimsByOfferId[parsed.offerId] = parsed
                    _claimedOfferIds.update { it + parsed.offerId }
                    rebuildIncomingOffers()
                    recomputeUnlistedClaims(me)
                }
            }
            EventKind.TOKEN_LEDGER -> {
                val parsed = DailyTokens.parseLedger(event, requireSig = true) ?: return
                if (parsed.slug != DailyTokens.STEAK_SLUG || !parsed.isPrimary) return
                val existing = observedLedgers[parsed.ownerPubkey]
                if (existing == null || parsed.event.createdAt >= existing.event.createdAt) {
                    observedLedgers[parsed.ownerPubkey] = parsed
                    if (parsed.ownerPubkey == me) {
                        recomputeUnlistedClaims(me)
                    }
                    loadProfileSteaks(parsed.ownerPubkey, forceRefresh = true)
                }
            }
        }
    }

    private fun recomputeCooldown(me: String) {
        _cooldownUntilByRecipient.value = DailyTokens.cooldownUntilByRecipient(
            senderPubkey = me,
            slug = DailyTokens.STEAK_SLUG,
            offers = mySentOffers.values,
            nowSeconds = nowEpochSeconds(),
        )
    }

    private fun rebuildIncomingOffers() {
        val claiming = _claimingOfferIds.value
        val claimedIds = _claimedOfferIds.value
        val list = myIncomingOffers.values
            .map { offer ->
                val claim = myClaimsByOfferId[offer.offerId]
                val isClaimed = claim != null || offer.offerId in claimedIds
                IncomingSteakOffer(
                    offer = offer,
                    claimed = isClaimed,
                    claimId = claim?.claimId,
                    claimedAt = claim?.claimedAt,
                    claiming = offer.offerId in claiming,
                )
            }
            .sortedByDescending { it.offer.offeredAt }
        _incomingOffers.value = list
    }

    private fun recomputeUnlistedClaims(me: String) {
        val ledgerOfferIds = observedLedgers[me]?.entries?.map { it.offerId }?.toSet().orEmpty()
        val cachedOfferIds = _profileSummaries.value[me]?.receipts?.map { it.offerId }?.toSet().orEmpty()
        val knownListed = ledgerOfferIds + cachedOfferIds
        val unlisted = myClaimsByOfferId.keys.count { it !in knownListed }
        _unlistedClaimCount.value = unlisted
    }

    // ── Sending a Steak (kind 8701) ───────────────────────────────────────────

    /**
     * Mines 16-bit NIP-13 PoW, signs, and broadcasts a `kind: 8701` Steak Offer
     * to [recipientPubkey]. Enforces self-send rejection and rolling 24h cooldown.
     */
    suspend fun sendSteak(recipientPubkey: String): Result<Event> = sendMutex.withLock {
        val account = accountRepository.activeAccount.first()
            ?: return@withLock Result.failure(IllegalStateException("No active account"))
        val me = account.pubkey
        if (recipientPubkey == me) {
            return@withLock Result.failure(IllegalArgumentException("You can't send a steak to yourself"))
        }
        val signer = signerFactory.forActiveAccount()
            ?: return@withLock Result.failure(IllegalStateException("No signer available"))
        if (signer.pubkey != me) {
            return@withLock Result.failure(IllegalStateException("Active account changed"))
        }
        if (!hasConnectedRelay()) {
            return@withLock Result.failure(IllegalStateException("No connected relays — try again when online"))
        }

        _sendingToPubkey.value = recipientPubkey
        try {
            // Check relays for recent offers from us to this specific recipient to avoid racing another device.
            val nowCheck = nowEpochSeconds()
            val recentRelayOffers = queryOnce(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.TOKEN_OFFER),
                        authors = listOf(me),
                        pTags = listOf(recipientPubkey),
                        tTags = listOf(DailyTokens.STEAK_SLUG),
                        since = nowCheck - DailyTokens.COOLDOWN_SECONDS,
                        limit = 10,
                    ),
                ),
                label = "steak-cooldown-check",
                timeoutMs = 2_500L,
            )
            recentRelayOffers.forEach { ev ->
                DailyTokens.parseOffer(ev, nowCheck, requireSig = true)
                    ?.takeIf {
                        it.slug == DailyTokens.STEAK_SLUG &&
                            it.senderPubkey == me &&
                            it.recipientPubkey == recipientPubkey
                    }
                    ?.let { mySentOffers[it.offerId] = it }
            }
            recomputeCooldown(me)

            val now = nowEpochSeconds()
            val unlockAt = DailyTokens.cooldownUntilForRecipient(
                senderPubkey = me,
                recipientPubkey = recipientPubkey,
                slug = DailyTokens.STEAK_SLUG,
                offers = mySentOffers.values,
                nowSeconds = now,
            )
            if (unlockAt > now) {
                val wait = DailyTokens.formatRemainingDuration(unlockAt - now)
                return@withLock Result.failure(
                    IllegalStateException("You already sent them a steak — next ready in $wait"),
                )
            }

            val relayHint = account.effectiveRelays().firstOrNull()
            val unsigned = DailyTokens.buildUnsignedOffer(
                senderPubkey = me,
                recipientPubkey = recipientPubkey,
                slug = DailyTokens.STEAK_SLUG,
                nowSeconds = now,
                relayHint = relayHint,
            )
            val mined = withContext(Dispatchers.Default) {
                Nip13.mine(unsigned, DailyTokens.MIN_POW_BITS)
            }
            val signed = signer.signEvent(mined).getOrElse { return@withLock Result.failure(it) }
            val parsed = DailyTokens.parseOffer(signed, nowEpochSeconds(), requireSig = true)
                ?: return@withLock Result.failure(IllegalStateException("Signed steak offer failed verification"))

            if (!hasConnectedRelay()) {
                return@withLock Result.failure(IllegalStateException("Relays disconnected before publishing"))
            }
            pool.publish(signed)

            mySentOffers[parsed.offerId] = parsed
            recomputeCooldown(me)
            Result.success(signed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "sendSteak failed")
            Result.failure(e)
        } finally {
            _sendingToPubkey.value = null
        }
    }

    // ── Claiming a Steak (kind 8702 + kind 38701) ─────────────────────────────

    /**
     * Accepts a pending `kind: 8701` Steak Offer within its 24-hour claim window:
     *  1. Mines 16-bit NIP-13 PoW and publishes immutable `kind: 8702` Token Claim.
     *  2. Fetches & updates addressable `kind: 38701` Profile Token Ledger (`d = "steak"`),
     *     archiving overflow shards automatically past 500 steaks.
     *  3. Persists the verified receipt into Room so profile & leaderboard update immediately.
     */
    suspend fun claimSteak(offerEvent: Event): Result<DailyTokens.VerifiedTokenReceipt> = claimMutex.withLock {
        val account = accountRepository.activeAccount.first()
            ?: return@withLock Result.failure(IllegalStateException("No active account"))
        val me = account.pubkey
        val signer = signerFactory.forActiveAccount()
            ?: return@withLock Result.failure(IllegalStateException("No signer available"))
        if (signer.pubkey != me) {
            return@withLock Result.failure(IllegalStateException("Active account changed"))
        }

        val now = nowEpochSeconds()
        val offer = DailyTokens.parseOffer(offerEvent, now, requireSig = true)
            ?: return@withLock Result.failure(IllegalArgumentException("Invalid steak offer"))
        if (offer.slug != DailyTokens.STEAK_SLUG || offer.recipientPubkey != me) {
            return@withLock Result.failure(IllegalArgumentException("Steak offer is not addressed to your account"))
        }
        if (offer.offerId in _claimedOfferIds.value || myClaimsByOfferId.containsKey(offer.offerId)) {
            return@withLock Result.failure(IllegalStateException("Steak already claimed"))
        }
        if (now > offer.expiresAt) {
            return@withLock Result.failure(IllegalStateException("This steak went cold after 24 hours"))
        }
        if (!hasConnectedRelay()) {
            return@withLock Result.failure(IllegalStateException("No connected relays — try again when online"))
        }

        _claimingOfferIds.update { it + offer.offerId }
        rebuildIncomingOffers()
        try {
            // Double-check whether we already claimed this offer on another device.
            val existingClaims = queryOnce(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.TOKEN_CLAIM),
                        authors = listOf(me),
                        eTags = listOf(offer.offerId),
                        tTags = listOf(DailyTokens.STEAK_SLUG),
                        limit = 5,
                    ),
                ),
                label = "steak-claim-check",
                timeoutMs = 2_000L,
            ).mapNotNull { DailyTokens.parseClaim(it, requireSig = true) }
                .filter { DailyTokens.isValidPair(offer, it, me, DailyTokens.STEAK_SLUG) }
                .sortedWith(compareBy<DailyTokens.ParsedTokenClaim> { it.claimedAt }.thenBy { it.claimId })

            val relayHint = account.effectiveRelays().firstOrNull() ?: offer.relayHint
            val claim: DailyTokens.ParsedTokenClaim = if (existingClaims.isNotEmpty()) {
                existingClaims.first()
            } else {
                val unsignedClaim = DailyTokens.buildUnsignedClaim(
                    recipientPubkey = me,
                    offer = offer,
                    nowSeconds = nowEpochSeconds(),
                    relayHint = relayHint,
                ).getOrElse { return@withLock Result.failure(it) }

                val minedClaim = withContext(Dispatchers.Default) {
                    Nip13.mine(unsignedClaim, DailyTokens.MIN_POW_BITS)
                }
                val signedClaim = signer.signEvent(minedClaim).getOrElse { return@withLock Result.failure(it) }
                val parsedClaim = DailyTokens.parseClaim(signedClaim, requireSig = true)
                    ?: return@withLock Result.failure(IllegalStateException("Signed steak claim failed verification"))
                if (!DailyTokens.isValidPair(offer, parsedClaim, me, DailyTokens.STEAK_SLUG)) {
                    return@withLock Result.failure(IllegalStateException("Claim failed 24-hour window verification"))
                }
                pool.publish(signedClaim)
                parsedClaim
            }

            // Record the published claim immediately so even if step 2 (ledger update)
            // is interrupted or cancelled on an external signer, the steak is preserved.
            myClaimsByOfferId[offer.offerId] = claim
            myIncomingOffers[offer.offerId] = offer
            _claimedOfferIds.update { it + offer.offerId }

            val entry = DailyTokens.LedgerEntry(
                claimId = claim.claimId,
                offerId = offer.offerId,
                relayHint = relayHint,
            )
            val ledgerResult = publishLedgerAppend(me, signer, listOf(entry))
            if (ledgerResult.isFailure) {
                recomputeUnlistedClaims(me)
                return@withLock Result.failure(
                    IllegalStateException(
                        "Steak claim published, but updating your profile shelf failed — tap Sync Shelf to retry",
                        ledgerResult.exceptionOrNull(),
                    ),
                )
            }

            val receipt = DailyTokens.VerifiedTokenReceipt(
                slug = DailyTokens.STEAK_SLUG,
                recipientPubkey = me,
                senderPubkey = offer.senderPubkey,
                offerId = offer.offerId,
                claimId = claim.claimId,
                offeredAt = offer.offeredAt,
                claimedAt = claim.claimedAt,
                relayHint = relayHint,
            )
            persistNewReceipt(me, receipt, ledgerResult.getOrNull())
            recomputeUnlistedClaims(me)
            Result.success(receipt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "claimSteak failed")
            Result.failure(e)
        } finally {
            _claimingOfferIds.update { it - offer.offerId }
            rebuildIncomingOffers()
        }
    }

    /** Repairs any published `kind: 8702` Claims missing from the active user's `kind: 38701` ledger. */
    suspend fun syncUnlistedClaims(): Result<Int> = claimMutex.withLock {
        val account = accountRepository.activeAccount.first()
            ?: return@withLock Result.failure(IllegalStateException("No active account"))
        val me = account.pubkey
        val signer = signerFactory.forActiveAccount()
            ?: return@withLock Result.failure(IllegalStateException("No signer available"))
        val existingLedger = fetchLatestPrimaryLedger(me)
        val listedIds = existingLedger?.entries?.map { it.offerId }?.toSet().orEmpty()
        val missing = myClaimsByOfferId.values
            .filter { it.offerId !in listedIds }
            .map { DailyTokens.LedgerEntry(it.claimId, it.offerId, it.relayHint) }
        if (missing.isEmpty()) {
            _unlistedClaimCount.value = 0
            return@withLock Result.success(0)
        }
        val published = publishLedgerAppend(me, signer, missing)
            .getOrElse { return@withLock Result.failure(it) }
        observedLedgers[me] = published
        recomputeUnlistedClaims(me)
        loadProfileSteaks(me, forceRefresh = true)
        Result.success(missing.size)
    }

    private suspend fun publishLedgerAppend(
        me: String,
        signer: social.tbone.account.signer.NostrSigner,
        newEntries: List<DailyTokens.LedgerEntry>,
    ): Result<DailyTokens.ParsedTokenLedger> {
        var currentPrimary = fetchLatestPrimaryLedger(me)
        var prevCreatedAt = currentPrimary?.event?.createdAt ?: 0L
        var plan = DailyTokens.LedgerPlan(
            primaryEntries = currentPrimary?.entries.orEmpty(),
            shardAddresses = currentPrimary?.shardAddresses.orEmpty(),
        )
        for (entry in newEntries) {
            plan = DailyTokens.planLedgerAppend(
                ownerPubkey = me,
                slug = DailyTokens.STEAK_SLUG,
                existingPrimary = currentPrimary?.copy(
                    entries = plan.primaryEntries,
                    shardAddresses = plan.shardAddresses,
                ) ?: if (plan.primaryEntries.isNotEmpty() || plan.shardAddresses.isNotEmpty()) {
                    // Synthesize in-memory state when appending multiple entries to a fresh ledger.
                    DailyTokens.ParsedTokenLedger(
                        event = Event("", me, prevCreatedAt, EventKind.TOKEN_LEDGER, emptyList(), "", ""),
                        slug = DailyTokens.STEAK_SLUG,
                        shardIndex = null,
                        entries = plan.primaryEntries,
                        shardAddresses = plan.shardAddresses,
                    )
                } else null,
                newEntry = entry,
            )
            // Publish any overflow shard before updating primary so the `a` pointer resolves immediately.
            plan.newShard?.let { (shardIdx, archivedEntries) ->
                val shardUnsigned = DailyTokens.buildUnsignedShard(
                    ownerPubkey = me,
                    slug = DailyTokens.STEAK_SLUG,
                    shardIndex = shardIdx,
                    entries = archivedEntries,
                    createdAt = nowEpochSeconds(),
                )
                val signedShard = signer.signEvent(shardUnsigned).getOrElse { return Result.failure(it) }
                pool.publish(signedShard)
            }
        }

        val nextCreatedAt = maxOf(nowEpochSeconds(), prevCreatedAt + 1L)
        val primaryUnsigned = DailyTokens.buildUnsignedLedger(
            ownerPubkey = me,
            slug = DailyTokens.STEAK_SLUG,
            entries = plan.primaryEntries,
            shardAddresses = plan.shardAddresses,
            createdAt = nextCreatedAt,
        )
        val signedPrimary = signer.signEvent(primaryUnsigned).getOrElse { return Result.failure(it) }
        val parsedPrimary = DailyTokens.parseLedger(signedPrimary, requireSig = true)
            ?: return Result.failure(IllegalStateException("Signed profile steak ledger failed verification"))
        pool.publish(signedPrimary)
        observedLedgers[me] = parsedPrimary
        return Result.success(parsedPrimary)
    }

    private suspend fun persistNewReceipt(
        me: String,
        receipt: DailyTokens.VerifiedTokenReceipt,
        ledger: DailyTokens.ParsedTokenLedger?,
    ) {
        val now = nowEpochSeconds()
        runCatching {
            tokenCacheDao.upsertVerifiedTokens(listOf(receipt.toEntity(now)))
            val allMine = tokenCacheDao.getVerifiedForRecipient(DailyTokens.STEAK_SLUG, me)
            val receipts = allMine.map { it.toReceipt() }
            if (ledger != null) {
                tokenCacheDao.upsertLedgerState(
                    TokenLedgerStateEntity(
                        pubkey = me,
                        slug = DailyTokens.STEAK_SLUG,
                        ledgerEventId = ledger.event.id,
                        ledgerCreatedAt = ledger.event.createdAt,
                        verifiedCount = receipts.size,
                        latestClaimedAt = receipts.maxOfOrNull { it.claimedAt } ?: receipt.claimedAt,
                        updatedAt = now,
                    ),
                )
            }
            _profileSummaries.update { map ->
                map + (me to ProfileSteakSummary(
                    pubkey = me,
                    receipts = receipts,
                    isLoading = false,
                    lastSyncedAt = now,
                ))
            }
        }.onFailure { Timber.w(it, "Failed persisting verified steak receipt") }
    }

    // ── Profile Steak Verification ────────────────────────────────────────────

    /**
     * Loads and cryptographically verifies [pubkey]'s Profile Steak Ledger.
     * Emits cached Room results immediately, then refreshes from relays.
     */
    fun loadProfileSteaks(pubkey: String, forceRefresh: Boolean = false) {
        if (!DailyTokens.isLowerHex64(pubkey)) return
        val existing = _profileSummaries.value[pubkey]
        val now = nowEpochSeconds()
        if (!forceRefresh && existing != null && !existing.isLoading && now - existing.lastSyncedAt < PROFILE_FRESH_SECONDS) {
            return
        }
        activeProfileRefreshJobs[pubkey]?.cancel()
        val job = scope.launch {
            // 1. Seed immediately from Room cache so UI never flashes 0.
            val cached = runCatching {
                tokenCacheDao.getVerifiedForRecipient(DailyTokens.STEAK_SLUG, pubkey)
            }.getOrDefault(emptyList())
            _profileSummaries.update { map ->
                map + (pubkey to ProfileSteakSummary(
                    pubkey = pubkey,
                    receipts = cached.map { it.toReceipt() },
                    isLoading = true,
                    lastSyncedAt = existing?.lastSyncedAt ?: 0L,
                ))
            }

            try {
                val primary = fetchLatestPrimaryLedger(pubkey)
                if (primary == null) {
                    _profileSummaries.update { map ->
                        val cur = map[pubkey]
                        map + (pubkey to (cur ?: ProfileSteakSummary(pubkey)).copy(
                            isLoading = false,
                            lastSyncedAt = nowEpochSeconds(),
                        ))
                    }
                    return@launch
                }
                observedLedgers[pubkey] = primary

                val verified = verifyLedgerWithCache(primary, auditSenderCooldowns = true)
                val syncedAt = nowEpochSeconds()
                persistVerifiedProfile(pubkey, primary, verified, syncedAt)

                // Request sender profiles so the steak history sheet displays names & avatars.
                val senders = verified.map { it.senderPubkey }.distinct()
                if (senders.isNotEmpty()) profileRepository.requestProfiles(senders)

                _profileSummaries.update { map ->
                    map + (pubkey to ProfileSteakSummary(
                        pubkey = pubkey,
                        receipts = verified,
                        isLoading = false,
                        lastSyncedAt = syncedAt,
                    ))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "loadProfileSteaks failed for ${pubkey.take(8)}")
                _profileSummaries.update { map ->
                    val cur = map[pubkey]
                    map + (pubkey to (cur ?: ProfileSteakSummary(pubkey)).copy(isLoading = false))
                }
            }
        }
        activeProfileRefreshJobs[pubkey] = job
    }

    // ── Leaderboard (Follows & Global) ────────────────────────────────────────

    /**
     * Loads and verifies the Steak Leaderboard for [scopeType] (`FOLLOWS` or `GLOBAL`).
     * Seeds instantly from Room cache, then fetches & verifies `kind: 38701` ledgers from relays.
     */
    fun refreshLeaderboard(scopeType: LeaderboardScope) {
        leaderboardJob?.cancel()
        leaderboardJob = scope.launch {
            _leaderboardLoading.value = true
            try {
                val account = accountRepository.activeAccount.first()
                val followSet = buildSet {
                    account?.pubkey?.let(::add)
                    account?.follows?.let(::addAll)
                }

                // 1. Instant seed from Room verified cache.
                val cachedRows = runCatching {
                    tokenCacheDao.getLeaderboardRows(DailyTokens.STEAK_SLUG, LEADERBOARD_LIMIT)
                }.getOrDefault(emptyList())
                emitLeaderboard(scopeType, cachedRows, followSet)

                if (!hasConnectedRelay()) return@launch

                // 2. Discover Profile Token Ledgers (`kind: 38701`, `#d = ["steak"]`) on relays.
                val ledgerEvents = when (scopeType) {
                    LeaderboardScope.FOLLOWS -> {
                        if (followSet.isEmpty()) emptyList()
                        else followSet.chunked(AUTHOR_QUERY_CHUNK).flatMap { chunk ->
                            queryOnce(
                                filters = listOf(
                                    Filter(
                                        kinds = listOf(EventKind.TOKEN_LEDGER),
                                        authors = chunk,
                                        dTags = listOf(DailyTokens.STEAK_SLUG),
                                        limit = chunk.size * 2,
                                    ),
                                ),
                                label = "steak-lb-follows",
                                timeoutMs = 4_000L,
                            )
                        }
                    }
                    LeaderboardScope.GLOBAL -> {
                        queryOnce(
                            filters = listOf(
                                Filter(
                                    kinds = listOf(EventKind.TOKEN_LEDGER),
                                    dTags = listOf(DailyTokens.STEAK_SLUG),
                                    limit = LEADERBOARD_LIMIT,
                                ),
                            ),
                            label = "steak-lb-global",
                            timeoutMs = 4_500L,
                        )
                    }
                }

                // Keep newest valid primary ledger per pubkey.
                val latestByAuthor = LinkedHashMap<String, DailyTokens.ParsedTokenLedger>()
                for (ev in ledgerEvents) {
                    val parsed = DailyTokens.parseLedger(ev, requireSig = true) ?: continue
                    if (parsed.slug != DailyTokens.STEAK_SLUG || !parsed.isPrimary) continue
                    val prev = latestByAuthor[parsed.ownerPubkey]
                    if (prev == null || parsed.event.createdAt > prev.event.createdAt ||
                        (parsed.event.createdAt == prev.event.createdAt && parsed.event.id < prev.event.id)
                    ) {
                        latestByAuthor[parsed.ownerPubkey] = parsed
                    }
                }
                if (latestByAuthor.isEmpty()) return@launch

                // 3. Batch-verify all discovered ledgers (reusing Room verified cache first!).
                verifyLedgersBatch(latestByAuthor.values.toList())

                // 4. Reload cleanly ranked verified rows from Room & request their profiles.
                val updatedRows = runCatching {
                    tokenCacheDao.getLeaderboardRows(DailyTokens.STEAK_SLUG, LEADERBOARD_LIMIT)
                }.getOrDefault(emptyList())
                emitLeaderboard(scopeType, updatedRows, followSet)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "refreshLeaderboard failed for $scopeType")
            } finally {
                _leaderboardLoading.value = false
            }
        }
    }

    private fun emitLeaderboard(
        scopeType: LeaderboardScope,
        rows: List<TokenLedgerStateEntity>,
        followSet: Set<String>,
    ) {
        val filtered = when (scopeType) {
            LeaderboardScope.FOLLOWS -> rows.filter { it.pubkey in followSet }
            LeaderboardScope.GLOBAL -> rows
        }
        val ranked = filtered
            .sortedWith(
                compareByDescending<TokenLedgerStateEntity> { it.verifiedCount }
                    .thenByDescending { it.latestClaimedAt }
                    .thenBy { it.pubkey },
            )
            .mapIndexed { idx, row ->
                SteakLeaderboardEntry(
                    pubkey = row.pubkey,
                    verifiedCount = row.verifiedCount,
                    latestClaimedAt = row.latestClaimedAt,
                    rank = idx + 1,
                )
            }
        when (scopeType) {
            LeaderboardScope.FOLLOWS -> _followsLeaderboard.value = ranked
            LeaderboardScope.GLOBAL -> _globalLeaderboard.value = ranked
        }
        val pubkeys = ranked.take(60).map { it.pubkey }
        if (pubkeys.isNotEmpty()) profileRepository.requestProfiles(pubkeys)
    }

    // ── Shared Verification Pipeline ──────────────────────────────────────────

    private suspend fun verifyLedgerWithCache(
        primary: DailyTokens.ParsedTokenLedger,
        auditSenderCooldowns: Boolean,
    ): List<DailyTokens.VerifiedTokenReceipt> {
        val shards = fetchShardsFor(primary)
        val allEntries = collectAllEntries(primary, shards)
        if (allEntries.isEmpty()) return emptyList()

        val cachedByOffer = fetchCachedVerified(allEntries.keys)
        val uncachedEntries = allEntries.values.filter { entry ->
            val cached = cachedByOffer[entry.offerId]
            cached == null || cached.claimId != entry.claimId || cached.recipientPubkey != primary.ownerPubkey
        }

        val fetchedEvents = if (uncachedEntries.isEmpty()) {
            emptyMap()
        } else {
            val neededIds = uncachedEntries.flatMap { listOf(it.offerId, it.claimId) }.distinct()
            fetchEventsByIds(neededIds)
        }

        val now = nowEpochSeconds()
        val offersById = HashMap<String, DailyTokens.ParsedTokenOffer>()
        val claimsById = HashMap<String, DailyTokens.ParsedTokenClaim>()
        for (entry in uncachedEntries) {
            fetchedEvents[entry.offerId]?.let { ev ->
                DailyTokens.parseOffer(ev, now, requireSig = true)?.let { offersById[it.offerId] = it }
            }
            fetchedEvents[entry.claimId]?.let { ev ->
                DailyTokens.parseClaim(ev, requireSig = true)?.let { claimsById[it.claimId] = it }
            }
        }

        // Optional deeper cooldown audit: check each sender's surrounding 8701 history to this recipient on relays.
        val extraSenderOffers = if (auditSenderCooldowns && offersById.isNotEmpty()) {
            fetchSurroundingSenderOffers(primary.ownerPubkey, offersById.values)
        } else {
            emptyList()
        }

        val newlyVerified = DailyTokens.verifyProfileTokens(
            recipientPubkey = primary.ownerPubkey,
            slug = DailyTokens.STEAK_SLUG,
            primaryLedger = primary,
            shardLedgers = shards,
            offersById = offersById,
            claimsById = claimsById,
            extraSenderOffers = extraSenderOffers,
        )

        // Combine cached verified receipts (still present in the ledger!) with newly verified ones,
        // and enforce sender cooldown across the combined set so split refreshes cannot bypass 24h cooldown.
        val retainedCached = allEntries.values.mapNotNull { entry ->
            val c = cachedByOffer[entry.offerId] ?: return@mapNotNull null
            if (c.claimId == entry.claimId && c.recipientPubkey == primary.ownerPubkey) c.toReceipt() else null
        }
        return enforceCooldownAcrossReceipts(retainedCached + newlyVerified)
    }

    private suspend fun verifyLedgersBatch(ledgers: List<DailyTokens.ParsedTokenLedger>) {
        if (ledgers.isEmpty()) return
        val now = nowEpochSeconds()

        // 1. Fetch shards for any ledger that carries shard `a` pointers.
        val shardsByOwner = HashMap<String, Map<String, DailyTokens.ParsedTokenLedger>>()
        for (ledger in ledgers) {
            if (ledger.shardAddresses.isNotEmpty()) {
                shardsByOwner[ledger.ownerPubkey] = fetchShardsFor(ledger)
            }
        }

        // 2. Gather every referenced (offerId, claimId) across all candidate ledgers,
        //    skipping pairs already verified in Room.
        val entriesByOwner = HashMap<String, Map<String, DailyTokens.LedgerEntry>>()
        val allOfferIds = LinkedHashSet<String>()
        for (ledger in ledgers) {
            val entries = collectAllEntries(ledger, shardsByOwner[ledger.ownerPubkey].orEmpty())
            entriesByOwner[ledger.ownerPubkey] = entries
            allOfferIds.addAll(entries.keys)
        }
        val cachedByOffer = fetchCachedVerified(allOfferIds)

        val neededIds = LinkedHashSet<String>()
        for (ledger in ledgers) {
            val entries = entriesByOwner[ledger.ownerPubkey].orEmpty()
            var uncachedForOwner = 0
            for (entry in entries.values) {
                val cached = cachedByOffer[entry.offerId]
                if (cached == null || cached.claimId != entry.claimId || cached.recipientPubkey != ledger.ownerPubkey) {
                    if (uncachedForOwner >= MAX_UNCACHED_PAIRS_PER_LEDGER_IN_LEADERBOARD) break
                    neededIds.add(entry.offerId)
                    neededIds.add(entry.claimId)
                    uncachedForOwner++
                }
            }
            if (neededIds.size >= MAX_LEADERBOARD_VERIFY_IDS) break
        }

        // 3. Batch-fetch all uncached offer/claim events across relays (bounded to keep UI snappy).
        val fetchedEvents = if (neededIds.isEmpty()) {
            emptyMap()
        } else {
            fetchEventsByIds(neededIds.take(MAX_LEADERBOARD_VERIFY_IDS))
        }

        val offersById = HashMap<String, DailyTokens.ParsedTokenOffer>()
        val claimsById = HashMap<String, DailyTokens.ParsedTokenClaim>()
        for (ev in fetchedEvents.values) {
            when (ev.kind) {
                EventKind.TOKEN_OFFER ->
                    DailyTokens.parseOffer(ev, now, requireSig = true)?.let { offersById[it.offerId] = it }
                EventKind.TOKEN_CLAIM ->
                    DailyTokens.parseClaim(ev, requireSig = true)?.let { claimsById[it.claimId] = it }
            }
        }

        // 4. Verify each owner's ledger and persist verified counts to Room.
        for (ledger in ledgers) {
            val owner = ledger.ownerPubkey
            val shards = shardsByOwner[owner].orEmpty()
            val entries = entriesByOwner[owner].orEmpty()
            val newlyVerified = DailyTokens.verifyProfileTokens(
                recipientPubkey = owner,
                slug = DailyTokens.STEAK_SLUG,
                primaryLedger = ledger,
                shardLedgers = shards,
                offersById = offersById,
                claimsById = claimsById,
            )
            val retainedCached = entries.values.mapNotNull { entry ->
                val c = cachedByOffer[entry.offerId] ?: return@mapNotNull null
                if (c.claimId == entry.claimId && c.recipientPubkey == owner) c.toReceipt() else null
            }
            val verified = enforceCooldownAcrossReceipts(retainedCached + newlyVerified)
            persistVerifiedProfile(owner, ledger, verified, now)
        }
    }

    private fun enforceCooldownAcrossReceipts(
        receipts: List<DailyTokens.VerifiedTokenReceipt>,
    ): List<DailyTokens.VerifiedTokenReceipt> {
        val unique = receipts.distinctBy { it.offerId }
        val kept = mutableListOf<DailyTokens.VerifiedTokenReceipt>()
        for ((_, byPair) in unique.groupBy { it.senderPubkey to it.recipientPubkey }) {
            val sorted = byPair.sortedWith(
                compareBy<DailyTokens.VerifiedTokenReceipt> { it.offeredAt }.thenBy { it.offerId },
            )
            var lastValidAt: Long? = null
            for (r in sorted) {
                val prev = lastValidAt
                if (prev == null || r.offeredAt - prev >= DailyTokens.COOLDOWN_SECONDS) {
                    kept += r
                    lastValidAt = r.offeredAt
                }
            }
        }
        return kept.sortedWith(
            compareByDescending<DailyTokens.VerifiedTokenReceipt> { it.claimedAt }.thenBy { it.offerId },
        )
    }

    private suspend fun persistVerifiedProfile(
        pubkey: String,
        primary: DailyTokens.ParsedTokenLedger,
        verified: List<DailyTokens.VerifiedTokenReceipt>,
        syncedAt: Long,
    ) {
        runCatching {
            if (verified.isEmpty()) {
                tokenCacheDao.clearRecipientTokens(DailyTokens.STEAK_SLUG, pubkey)
            } else {
                tokenCacheDao.upsertVerifiedTokens(verified.map { it.toEntity(syncedAt) })
                // Prune removed entries only when within SQLite bind-arg safety margin.
                if (verified.size <= 800) {
                    tokenCacheDao.pruneRecipientTokensNotIn(
                        slug = DailyTokens.STEAK_SLUG,
                        recipientPubkey = pubkey,
                        keepOfferIds = verified.map { it.offerId },
                    )
                }
            }
            tokenCacheDao.upsertLedgerState(
                TokenLedgerStateEntity(
                    pubkey = pubkey,
                    slug = DailyTokens.STEAK_SLUG,
                    ledgerEventId = primary.event.id,
                    ledgerCreatedAt = primary.event.createdAt,
                    verifiedCount = verified.size,
                    latestClaimedAt = verified.maxOfOrNull { it.claimedAt } ?: 0L,
                    updatedAt = syncedAt,
                ),
            )
        }.onFailure { Timber.w(it, "persistVerifiedProfile failed for ${pubkey.take(8)}") }
    }

    private suspend fun fetchCachedVerified(offerIds: Collection<String>): Map<String, VerifiedTokenEntity> {
        if (offerIds.isEmpty()) return emptyMap()
        val result = HashMap<String, VerifiedTokenEntity>(offerIds.size)
        for (chunk in offerIds.chunked(400)) {
            runCatching {
                tokenCacheDao.getVerifiedByOfferIds(DailyTokens.STEAK_SLUG, chunk)
            }.getOrDefault(emptyList()).forEach { result[it.offerId] = it }
        }
        return result
    }

    private fun collectAllEntries(
        primary: DailyTokens.ParsedTokenLedger,
        shards: Map<String, DailyTokens.ParsedTokenLedger>,
    ): Map<String, DailyTokens.LedgerEntry> {
        val out = LinkedHashMap<String, DailyTokens.LedgerEntry>()
        for (addr in primary.shardAddresses) {
            shards[addr]?.entries?.forEach { out.putIfAbsent(it.offerId, it) }
        }
        primary.entries.forEach { out.putIfAbsent(it.offerId, it) }
        return out
    }

    private suspend fun fetchLatestPrimaryLedger(pubkey: String): DailyTokens.ParsedTokenLedger? {
        val events = queryOnce(
            filters = listOf(
                Filter(
                    kinds = listOf(EventKind.TOKEN_LEDGER),
                    authors = listOf(pubkey),
                    dTags = listOf(DailyTokens.STEAK_SLUG),
                    limit = 5,
                ),
            ),
            label = "steak-ledger",
            timeoutMs = 3_500L,
        )
        val relayBest = events
            .mapNotNull { DailyTokens.parseLedger(it, requireSig = true) }
            .filter { it.ownerPubkey == pubkey && it.slug == DailyTokens.STEAK_SLUG && it.isPrimary }
            .maxWithOrNull(compareBy<DailyTokens.ParsedTokenLedger> { it.event.createdAt }.thenByDescending { it.event.id })
        val localBest = observedLedgers[pubkey]
        return when {
            relayBest == null -> localBest
            localBest == null -> relayBest
            localBest.event.createdAt > relayBest.event.createdAt -> localBest
            else -> relayBest
        }
    }

    private suspend fun fetchShardsFor(
        primary: DailyTokens.ParsedTokenLedger,
    ): Map<String, DailyTokens.ParsedTokenLedger> {
        if (primary.shardAddresses.isEmpty()) return emptyMap()
        val dTags = primary.shardAddresses.mapNotNull { addr ->
            DailyTokens.parseShardIndexFromAddress(addr, primary.ownerPubkey, primary.slug)
                ?.let { idx -> DailyTokens.shardDTag(primary.slug, idx) }
        }.distinct()
        if (dTags.isEmpty()) return emptyMap()
        val events = queryOnce(
            filters = listOf(
                Filter(
                    kinds = listOf(EventKind.TOKEN_LEDGER),
                    authors = listOf(primary.ownerPubkey),
                    dTags = dTags,
                    limit = dTags.size * 2,
                ),
            ),
            label = "steak-shards",
            timeoutMs = 3_500L,
        )
        val out = HashMap<String, DailyTokens.ParsedTokenLedger>()
        for (ev in events) {
            val parsed = DailyTokens.parseLedger(ev, requireSig = true) ?: continue
            val idx = parsed.shardIndex ?: continue
            if (parsed.ownerPubkey != primary.ownerPubkey || parsed.slug != primary.slug) continue
            val addr = DailyTokens.shardAddress(primary.ownerPubkey, primary.slug, idx)
            val prev = out[addr]
            if (prev == null || parsed.event.createdAt > prev.event.createdAt) {
                out[addr] = parsed
            }
        }
        return out
    }

    private suspend fun fetchEventsByIds(ids: List<String>): Map<String, Event> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Event>(ids.size)
        for (chunk in ids.distinct().chunked(ID_QUERY_CHUNK)) {
            val events = queryOnce(
                filters = listOf(Filter(ids = chunk, limit = chunk.size)),
                label = "steak-verify-ids",
                timeoutMs = 4_000L,
            )
            events.forEach { ev -> out[ev.id] = ev }
        }
        return out
    }

    private suspend fun fetchSurroundingSenderOffers(
        recipientPubkey: String,
        offers: Collection<DailyTokens.ParsedTokenOffer>,
    ): List<DailyTokens.ParsedTokenOffer> {
        val senders = offers.map { it.senderPubkey }.distinct()
        if (senders.isEmpty()) return emptyList()
        val minAt = offers.minOf { it.offeredAt } - DailyTokens.COOLDOWN_SECONDS
        val maxAt = offers.maxOf { it.offeredAt }
        val now = nowEpochSeconds()
        val extra = mutableListOf<DailyTokens.ParsedTokenOffer>()
        for (chunk in senders.chunked(AUTHOR_QUERY_CHUNK)) {
            val events = queryOnce(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.TOKEN_OFFER),
                        authors = chunk,
                        pTags = listOf(recipientPubkey),
                        tTags = listOf(DailyTokens.STEAK_SLUG),
                        since = minAt.coerceAtLeast(0L),
                        until = maxAt,
                        limit = (chunk.size * 4).coerceAtMost(200),
                    ),
                ),
                label = "steak-sender-audit",
                timeoutMs = 3_000L,
            )
            events.mapNotNull { DailyTokens.parseOffer(it, now, requireSig = true) }
                .filterTo(extra) {
                    it.slug == DailyTokens.STEAK_SLUG && it.recipientPubkey == recipientPubkey
                }
        }
        return extra
    }

    // ── Relay query helper ────────────────────────────────────────────────────

    private fun hasConnectedRelay(): Boolean =
        pool.relayStatuses.value.values.any { it == RelayStatus.CONNECTED }

    private suspend fun queryOnce(
        filters: List<Filter>,
        label: String,
        timeoutMs: Long,
    ): List<Event> {
        val connectedRelays = pool.relayStatuses.value
            .filterValues { it == RelayStatus.CONNECTED }
            .keys
            .map(RelayPool::normalize)
            .toSet()
        if (connectedRelays.isEmpty()) return emptyList()

        val subId = "stk-${UUID.randomUUID().toString().take(8)}"
        val collected = LinkedHashMap<String, Event>()
        val eoseRelays = HashSet<String>()

        // Launch collector BEFORE subscribing so fast relays cannot beat subscription setup.
        val collectJob = scope.launch {
            pool.messages.collect { poolMessage ->
                when (val msg = poolMessage.message) {
                    is RelayMessage.EventMessage -> {
                        if (msg.subscriptionId == subId && msg.event.verify()) {
                            synchronized(collected) {
                                collected.putIfAbsent(msg.event.id, msg.event)
                            }
                        }
                    }
                    is RelayMessage.EndOfStoredEvents -> {
                        if (msg.subscriptionId == subId) {
                            synchronized(eoseRelays) {
                                eoseRelays.add(RelayPool.normalize(poolMessage.relayUrl))
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        try {
            pool.subscribe(filters, id = subId, label = label)
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val done = synchronized(eoseRelays) {
                        eoseRelays.containsAll(connectedRelays)
                    }
                    if (done) break
                    delay(40L)
                }
            }
        } finally {
            pool.unsubscribe(subId)
            collectJob.cancel()
        }
        return synchronized(collected) { collected.values.toList() }
    }

    /** Clears all in-memory state on duress wipe (Room tables cleared by AppDatabase.clearAllTables). */
    fun clearMemory() {
        synchronized(liveLock) {
            liveSubId?.let { pool.unsubscribe(it) }
            liveSubId = null
            liveCollectorJob?.cancel()
            liveCollectorJob = null
        }
        surfaces.reset()
        cachedAccountRelays.clear()
        activePubkey = null
        activeProfileRefreshJobs.values.forEach { it.cancel() }
        activeProfileRefreshJobs.clear()
        leaderboardJob?.cancel()
        leaderboardJob = null
        mySentOffers.clear()
        myIncomingOffers.clear()
        myClaimsByOfferId.clear()
        observedLedgers.clear()
        _cooldownUntilByRecipient.value = emptyMap()
        _incomingOffers.value = emptyList()
        _claimedOfferIds.value = emptySet()
        _claimingOfferIds.value = emptySet()
        _sendingToPubkey.value = null
        _profileSummaries.value = emptyMap()
        _followsLeaderboard.value = emptyList()
        _globalLeaderboard.value = emptyList()
        _leaderboardLoading.value = false
        _unlistedClaimCount.value = 0
    }

    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun VerifiedTokenEntity.toReceipt() = DailyTokens.VerifiedTokenReceipt(
        slug = slug,
        recipientPubkey = recipientPubkey,
        senderPubkey = senderPubkey,
        offerId = offerId,
        claimId = claimId,
        offeredAt = offeredAt,
        claimedAt = claimedAt,
        relayHint = relayHint,
    )

    private fun DailyTokens.VerifiedTokenReceipt.toEntity(verifiedAt: Long) = VerifiedTokenEntity(
        offerId = offerId,
        claimId = claimId,
        slug = slug,
        recipientPubkey = recipientPubkey,
        senderPubkey = senderPubkey,
        offeredAt = offeredAt,
        claimedAt = claimedAt,
        relayHint = relayHint,
        verifiedAt = verifiedAt,
    )

    companion object {
        private const val PROFILE_FRESH_SECONDS = 45L
        private const val LEADERBOARD_LIMIT = 200
        private const val AUTHOR_QUERY_CHUNK = 120
        private const val ID_QUERY_CHUNK = 80
        private const val MAX_LEADERBOARD_VERIFY_IDS = 1_200
        private const val MAX_UNCACHED_PAIRS_PER_LEDGER_IN_LEADERBOARD = 60
        /**
         * Upper bound on relays the live steak subscription talks to. Without a
         * cap a large relay list fans four filters back out across every one of
         * them, which is exactly the cost this subscription was moved off the
         * startup path to avoid.
         */
        private const val MAX_LIVE_RELAYS = 6
    }
}
