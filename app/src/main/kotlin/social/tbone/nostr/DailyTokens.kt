package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * NIP-XX: Daily Claimable Tokens (reference token: `steak`).
 *
 * Pure protocol parser, builder, and deterministic verifier implementing every
 * rule in `docs/NIP-daily-claimable-tokens.md`:
 *  - Kind `8701`: Token Offer (immutable, silent, 16-bit NIP-13 PoW, rolling 24h sender cooldown)
 *  - Kind `8702`: Token Claim (immutable, silent, 16-bit NIP-13 PoW, 24h claim window)
 *  - Kind `38701`: Profile Token Ledger (addressable `d = <slug>` + optional `<slug>:shard:<N>`)
 *
 * Zero network I/O and zero UI dependencies so every rule is 100% unit-testable.
 */
object DailyTokens {

    const val STEAK_SLUG = "steak"
    const val COOLDOWN_SECONDS = 86_400L
    const val CLAIM_WINDOW_SECONDS = 86_400L
    const val FUTURE_SKEW_SECONDS = 300L
    const val MIN_POW_BITS = 16
    const val MAX_CLAIMS_PER_LEDGER = 500

    private val SLUG_REGEX = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val HEX64_REGEX = Regex("^[0-9a-f]{64}$")
    private val SHARD_D_REGEX = Regex("^([a-z0-9][a-z0-9-]{0,31}):shard:([1-9][0-9]*)$")

    data class ParsedTokenOffer(
        val event: Event,
        val slug: String,
        val recipientPubkey: String,
        val relayHint: String? = null,
    ) {
        val offerId: String get() = event.id
        val senderPubkey: String get() = event.pubkey
        val offeredAt: Long get() = event.createdAt
        val expiresAt: Long get() = event.createdAt + CLAIM_WINDOW_SECONDS
    }

    data class ParsedTokenClaim(
        val event: Event,
        val slug: String,
        val offerId: String,
        val senderPubkey: String,
        val relayHint: String? = null,
    ) {
        val claimId: String get() = event.id
        val recipientPubkey: String get() = event.pubkey
        val claimedAt: Long get() = event.createdAt
    }

    data class LedgerEntry(
        val claimId: String,
        val offerId: String,
        val relayHint: String? = null,
    )

    data class ParsedTokenLedger(
        val event: Event,
        val slug: String,
        /** Null for primary ledger (`d = <slug>`); >= 1 for archive shards (`d = <slug>:shard:<N>`). */
        val shardIndex: Int?,
        val entries: List<LedgerEntry>,
        /** Validated `a`-tag shard addresses belonging strictly to [event.pubkey] and [slug]. */
        val shardAddresses: List<String>,
    ) {
        val ownerPubkey: String get() = event.pubkey
        val isPrimary: Boolean get() = shardIndex == null
    }

    data class VerifiedTokenReceipt(
        val slug: String,
        val recipientPubkey: String,
        val senderPubkey: String,
        val offerId: String,
        val claimId: String,
        val offeredAt: Long,
        val claimedAt: Long,
        val relayHint: String? = null,
    )

    data class LedgerPlan(
        val primaryEntries: List<LedgerEntry>,
        val shardAddresses: List<String>,
        /** Non-null when primary overflowed [MAX_CLAIMS_PER_LEDGER] and a new archive shard must be published first. */
        val newShard: Pair<Int, List<LedgerEntry>>? = null,
    )

    // ── Validation helpers ────────────────────────────────────────────────────

    fun isValidSlug(slug: String): Boolean = SLUG_REGEX.matches(slug)

    fun isLowerHex64(value: String?): Boolean = value != null && HEX64_REGEX.matches(value)

    fun shardDTag(slug: String, shardIndex: Int): String {
        require(isValidSlug(slug)) { "Invalid token slug: $slug" }
        require(shardIndex >= 1) { "shardIndex must be >= 1" }
        return "$slug:shard:$shardIndex"
    }

    fun shardAddress(ownerPubkey: String, slug: String, shardIndex: Int): String {
        require(isLowerHex64(ownerPubkey)) { "Invalid owner pubkey" }
        return "${EventKind.TOKEN_LEDGER}:$ownerPubkey:${shardDTag(slug, shardIndex)}"
    }

    /** Parses the shard index from an `a` address belonging to [ownerPubkey] and [slug], or null. */
    fun parseShardIndexFromAddress(address: String, ownerPubkey: String, slug: String): Int? {
        val prefix = "${EventKind.TOKEN_LEDGER}:$ownerPubkey:$slug:shard:"
        if (!address.startsWith(prefix)) return null
        val index = address.removePrefix(prefix).toIntOrNull() ?: return null
        return index.takeIf { it >= 1 }
    }

    // ── Event builders ────────────────────────────────────────────────────────

    fun buildUnsignedOffer(
        senderPubkey: String,
        recipientPubkey: String,
        slug: String = STEAK_SLUG,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        relayHint: String? = null,
    ): UnsignedEvent {
        require(isValidSlug(slug)) { "Invalid token slug: $slug" }
        require(isLowerHex64(senderPubkey)) { "Invalid sender pubkey" }
        require(isLowerHex64(recipientPubkey)) { "Invalid recipient pubkey" }
        require(senderPubkey != recipientPubkey) { "Cannot send a token to yourself" }

        val tags = listOf(
            tag("t", slug),
            if (!relayHint.isNullOrBlank()) tag("p", recipientPubkey, relayHint) else tag("p", recipientPubkey),
        )
        return UnsignedEvent(
            pubkey = senderPubkey,
            createdAt = nowSeconds,
            kind = EventKind.TOKEN_OFFER,
            tags = tags,
            content = "",
        )
    }

    /**
     * Builds an unsigned `kind: 8702` Claim for [offer], clamping `createdAt` to
     * `max(nowSeconds, offer.offeredAt)` so small clock skew never causes an
     * instant claim to fall before `offer.offeredAt`.
     *
     * Returns failure if the 24-hour claim window has already expired.
     */
    fun buildUnsignedClaim(
        recipientPubkey: String,
        offer: ParsedTokenOffer,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        relayHint: String? = null,
    ): Result<UnsignedEvent> = runCatching {
        require(isLowerHex64(recipientPubkey)) { "Invalid recipient pubkey" }
        require(recipientPubkey == offer.recipientPubkey) { "Offer is addressed to a different pubkey" }
        require(recipientPubkey != offer.senderPubkey) { "Cannot claim your own offer" }
        require(nowSeconds <= offer.expiresAt) { "This token expired after 24 hours" }

        val claimTime = maxOf(nowSeconds, offer.offeredAt)
        require(claimTime <= offer.expiresAt) { "This token expired after 24 hours" }

        val hint = relayHint?.takeIf { it.isNotBlank() } ?: offer.relayHint?.takeIf { it.isNotBlank() }
        val tags = listOf(
            tag("t", offer.slug),
            if (hint != null) tag("e", offer.offerId, hint) else tag("e", offer.offerId),
            tag("p", offer.senderPubkey),
        )
        UnsignedEvent(
            pubkey = recipientPubkey,
            createdAt = claimTime,
            kind = EventKind.TOKEN_CLAIM,
            tags = tags,
            content = "",
        )
    }

    /**
     * Plans an updated primary ledger after appending [newEntry], automatically
     * creating an overflow shard when the primary ledger exceeds [maxPerLedger].
     */
    fun planLedgerAppend(
        ownerPubkey: String,
        slug: String,
        existingPrimary: ParsedTokenLedger?,
        newEntry: LedgerEntry,
        maxPerLedger: Int = MAX_CLAIMS_PER_LEDGER,
    ): LedgerPlan {
        val currentShards = existingPrimary?.shardAddresses.orEmpty().distinct()
        val deduped = LinkedHashMap<String, LedgerEntry>()
        existingPrimary?.entries.orEmpty().forEach { deduped.putIfAbsent(it.offerId, it) }
        deduped.putIfAbsent(newEntry.offerId, newEntry)
        val combined = deduped.values.toList()
        if (combined.size <= maxPerLedger) {
            return LedgerPlan(
                primaryEntries = combined,
                shardAddresses = currentShards,
                newShard = null,
            )
        }
        val nextShardIndex = (currentShards.mapNotNull {
            parseShardIndexFromAddress(it, ownerPubkey, slug)
        }.maxOrNull() ?: 0) + 1
        val archived = combined.take(maxPerLedger)
        val remaining = combined.drop(maxPerLedger)
        val newAddress = shardAddress(ownerPubkey, slug, nextShardIndex)
        return LedgerPlan(
            primaryEntries = remaining,
            shardAddresses = currentShards + newAddress,
            newShard = nextShardIndex to archived,
        )
    }

    fun buildUnsignedLedger(
        ownerPubkey: String,
        slug: String = STEAK_SLUG,
        entries: List<LedgerEntry>,
        shardAddresses: List<String> = emptyList(),
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): UnsignedEvent {
        require(isLowerHex64(ownerPubkey)) { "Invalid owner pubkey" }
        require(isValidSlug(slug)) { "Invalid token slug: $slug" }
        val tags = buildList {
            add(tag("d", slug))
            shardAddresses.distinct().forEach { addr ->
                if (parseShardIndexFromAddress(addr, ownerPubkey, slug) != null) {
                    add(tag("a", addr))
                }
            }
            entries.distinctBy { it.offerId }.forEach { entry ->
                add(claimEntryTag(entry))
            }
        }
        return UnsignedEvent(
            pubkey = ownerPubkey,
            createdAt = createdAt,
            kind = EventKind.TOKEN_LEDGER,
            tags = tags,
            content = "",
        )
    }

    fun buildUnsignedShard(
        ownerPubkey: String,
        slug: String = STEAK_SLUG,
        shardIndex: Int,
        entries: List<LedgerEntry>,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): UnsignedEvent {
        require(isLowerHex64(ownerPubkey)) { "Invalid owner pubkey" }
        val dTag = shardDTag(slug, shardIndex)
        val tags = buildList {
            add(tag("d", dTag))
            entries.distinctBy { it.offerId }.forEach { entry ->
                add(claimEntryTag(entry))
            }
        }
        return UnsignedEvent(
            pubkey = ownerPubkey,
            createdAt = createdAt,
            kind = EventKind.TOKEN_LEDGER,
            tags = tags,
            content = "",
        )
    }

    // ── Event parsers ─────────────────────────────────────────────────────────

    fun parseOffer(
        event: Event,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        requireSig: Boolean = true,
        minPowBits: Int = MIN_POW_BITS,
    ): ParsedTokenOffer? {
        if (event.kind != EventKind.TOKEN_OFFER) return null
        if (event.content.isNotEmpty()) return null
        if (!isLowerHex64(event.pubkey) || !isLowerHex64(event.id)) return null
        if (event.createdAt > nowSeconds + FUTURE_SKEW_SECONDS) return null
        if (requireSig && !event.verify()) return null
        if (!Nip13.hasValidPow(event, minPowBits)) return null

        val tags = event.parsedTags
        // NIP-40 expiration tag is forbidden on Token Offers.
        if (tags.any { it.name == "expiration" }) return null

        val tTags = tags.filter { it.name == "t" }
        if (tTags.size != 1) return null
        val slug = tTags.first().value(1) ?: return null
        if (!isValidSlug(slug)) return null

        val pTags = tags.filter { it.name == "p" }
        if (pTags.size != 1) return null
        val recipient = pTags.first().value(1) ?: return null
        if (!isLowerHex64(recipient) || recipient == event.pubkey) return null

        val hint = pTags.first().value(2)?.takeIf { it.isNotBlank() }
        return ParsedTokenOffer(
            event = event,
            slug = slug,
            recipientPubkey = recipient,
            relayHint = hint,
        )
    }

    fun parseClaim(
        event: Event,
        requireSig: Boolean = true,
        minPowBits: Int = MIN_POW_BITS,
    ): ParsedTokenClaim? {
        if (event.kind != EventKind.TOKEN_CLAIM) return null
        if (event.content.isNotEmpty()) return null
        if (!isLowerHex64(event.pubkey) || !isLowerHex64(event.id)) return null
        if (requireSig && !event.verify()) return null
        if (!Nip13.hasValidPow(event, minPowBits)) return null

        val tags = event.parsedTags
        val tTags = tags.filter { it.name == "t" }
        if (tTags.size != 1) return null
        val slug = tTags.first().value(1) ?: return null
        if (!isValidSlug(slug)) return null

        val eTags = tags.filter { it.name == "e" }
        if (eTags.size != 1) return null
        val offerId = eTags.first().value(1) ?: return null
        if (!isLowerHex64(offerId)) return null
        val hint = eTags.first().value(2)?.takeIf { it.isNotBlank() }

        val pTags = tags.filter { it.name == "p" }
        if (pTags.size != 1) return null
        val sender = pTags.first().value(1) ?: return null
        if (!isLowerHex64(sender) || sender == event.pubkey) return null

        return ParsedTokenClaim(
            event = event,
            slug = slug,
            offerId = offerId,
            senderPubkey = sender,
            relayHint = hint,
        )
    }

    fun parseLedger(
        event: Event,
        requireSig: Boolean = true,
    ): ParsedTokenLedger? {
        if (event.kind != EventKind.TOKEN_LEDGER) return null
        if (event.content.isNotEmpty()) return null
        if (!isLowerHex64(event.pubkey) || !isLowerHex64(event.id)) return null
        if (requireSig && !event.verify()) return null

        val tags = event.parsedTags
        val dValue = tags.firstOrNull { it.name == "d" }?.value(1) ?: return null

        val slug: String
        val shardIndex: Int?
        val shardMatch = SHARD_D_REGEX.matchEntire(dValue)
        if (shardMatch != null) {
            slug = shardMatch.groupValues[1]
            shardIndex = shardMatch.groupValues[2].toIntOrNull() ?: return null
        } else {
            if (!isValidSlug(dValue)) return null
            slug = dValue
            shardIndex = null
        }

        val entries = LinkedHashMap<String, LedgerEntry>()
        val shards = LinkedHashSet<String>()
        for (t in tags) {
            when (t.name) {
                "claim" -> {
                    val claimId = t.value(1)
                    val offerId = t.value(2)
                    if (isLowerHex64(claimId) && isLowerHex64(offerId)) {
                        val hint = t.value(3)?.takeIf { it.isNotBlank() }
                        entries.putIfAbsent(offerId!!, LedgerEntry(claimId!!, offerId, hint))
                    }
                }
                "a" -> {
                    if (shardIndex == null) {
                        val addr = t.value(1)
                        if (addr != null && parseShardIndexFromAddress(addr, event.pubkey, slug) != null) {
                            shards.add(addr)
                        }
                    }
                }
            }
        }

        return ParsedTokenLedger(
            event = event,
            slug = slug,
            shardIndex = shardIndex,
            entries = entries.values.toList(),
            shardAddresses = shards.toList(),
        )
    }

    // ── Deterministic Cooldown & Pair Verification ────────────────────────────

    /**
     * Resolves which offers from a single `(sender, slug)` pass the rolling
     * 86,400-second cooldown rule.
     *
     * Offers are sorted deterministically by `(createdAt ASC, id ASC)`.
     * Unclaimed offers still consume the sender's cooldown so senders cannot
     * broadcast multiple offers hoping one is accepted.
     */
    fun resolveValidOfferIdsForSender(offers: Collection<ParsedTokenOffer>): Set<String> {
        if (offers.isEmpty()) return emptySet()
        val valid = HashSet<String>(offers.size)
        val bySenderAndSlug = offers
            .distinctBy { it.offerId }
            .groupBy { it.senderPubkey to it.slug }
        for ((_, group) in bySenderAndSlug) {
            val sorted = group.sortedWith(compareBy<ParsedTokenOffer> { it.offeredAt }.thenBy { it.offerId })
            var lastValidAt: Long? = null
            for (offer in sorted) {
                val prev = lastValidAt
                if (prev == null || offer.offeredAt - prev >= COOLDOWN_SECONDS) {
                    valid.add(offer.offerId)
                    lastValidAt = offer.offeredAt
                }
            }
        }
        return valid
    }

    /**
     * Verifies a single `(offer, claim)` pair for [recipientPubkey] and [slug]
     * against all structural, cryptographic, and 24-hour window rules (excluding
     * cross-offer sender cooldown, which is checked by [resolveValidOfferIdsForSender]).
     */
    fun isValidPair(
        offer: ParsedTokenOffer,
        claim: ParsedTokenClaim,
        recipientPubkey: String,
        slug: String,
    ): Boolean {
        if (offer.slug != slug || claim.slug != slug) return false
        if (offer.recipientPubkey != recipientPubkey) return false
        if (claim.recipientPubkey != recipientPubkey) return false
        if (offer.senderPubkey == recipientPubkey) return false
        if (claim.offerId != offer.offerId) return false
        if (claim.senderPubkey != offer.senderPubkey) return false
        if (claim.claimedAt < offer.offeredAt) return false
        if (claim.claimedAt > offer.offeredAt + CLAIM_WINDOW_SECONDS) return false
        return true
    }

    /**
     * End-to-end deterministic verification for a recipient's Profile Token Ledger
     * (primary + optional archive shards).
     *
     * - Only entries present in [primaryLedger] or a referenced shard in [shardLedgers]
     *   count (preserving NIP-58-style opt-in display control).
     * - Every entry MUST resolve to valid `(8701 Offer, 8702 Claim)` events in
     *   [offersById] and [claimsById].
     * - Duplicate references to the same `offerId` count at most once.
     * - Rolling 24-hour sender cooldown is enforced across all offers in
     *   `offersById.values + extraSenderOffers`.
     *
     * Returns verified receipts sorted newest-claimed first.
     */
    fun verifyProfileTokens(
        recipientPubkey: String,
        slug: String,
        primaryLedger: ParsedTokenLedger,
        shardLedgers: Map<String, ParsedTokenLedger> = emptyMap(),
        offersById: Map<String, ParsedTokenOffer>,
        claimsById: Map<String, ParsedTokenClaim>,
        extraSenderOffers: Collection<ParsedTokenOffer> = emptyList(),
    ): List<VerifiedTokenReceipt> {
        if (primaryLedger.ownerPubkey != recipientPubkey || primaryLedger.slug != slug || !primaryLedger.isPrimary) {
            return emptyList()
        }

        // Collect all unique ledger entries from primary + explicitly linked shards.
        val referenced = LinkedHashMap<String, LedgerEntry>()
        for (addr in primaryLedger.shardAddresses) {
            val shard = shardLedgers[addr] ?: continue
            if (shard.ownerPubkey == recipientPubkey && shard.slug == slug && shard.shardIndex != null) {
                for (entry in shard.entries) {
                    referenced.putIfAbsent(entry.offerId, entry)
                }
            }
        }
        for (entry in primaryLedger.entries) {
            referenced.putIfAbsent(entry.offerId, entry)
        }
        if (referenced.isEmpty()) return emptyList()

        // Evaluate sender rolling 24h cooldown across all observed valid offers.
        val allObservedOffers = buildList {
            addAll(offersById.values)
            addAll(extraSenderOffers)
        }.filter { it.slug == slug }
        val cooldownValidOfferIds = resolveValidOfferIdsForSender(allObservedOffers)

        val verified = mutableListOf<VerifiedTokenReceipt>()
        for ((offerId, entry) in referenced) {
            if (offerId !in cooldownValidOfferIds) continue
            val offer = offersById[offerId] ?: continue
            val claim = claimsById[entry.claimId] ?: continue
            if (!isValidPair(offer, claim, recipientPubkey, slug)) continue
            verified += VerifiedTokenReceipt(
                slug = slug,
                recipientPubkey = recipientPubkey,
                senderPubkey = offer.senderPubkey,
                offerId = offer.offerId,
                claimId = claim.claimId,
                offeredAt = offer.offeredAt,
                claimedAt = claim.claimedAt,
                relayHint = entry.relayHint ?: claim.relayHint ?: offer.relayHint,
            )
        }

        return verified.sortedWith(
            compareByDescending<VerifiedTokenReceipt> { it.claimedAt }
                .thenBy { it.offerId },
        )
    }

    /**
     * Computes when [senderPubkey]'s rolling 24-hour cooldown expires based on
     * their valid [offers] for [slug]. Returns `0L` if ready to send right now.
     */
    fun cooldownUntilEpochSeconds(
        senderPubkey: String,
        slug: String,
        offers: Collection<ParsedTokenOffer>,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Long {
        val mine = offers.filter { it.senderPubkey == senderPubkey && it.slug == slug }
        if (mine.isEmpty()) return 0L
        val validIds = resolveValidOfferIdsForSender(mine)
        val latestValid = mine.filter { it.offerId in validIds }.maxOfOrNull { it.offeredAt } ?: return 0L
        val unlockAt = latestValid + COOLDOWN_SECONDS
        return if (unlockAt > nowSeconds) unlockAt else 0L
    }

    /** Formats a remaining duration in seconds as `"18h 24m"`, `"42m"`, or `"< 1m"`. */
    fun formatRemainingDuration(remainingSeconds: Long): String {
        if (remainingSeconds <= 0L) return "expired"
        val hours = remainingSeconds / 3600L
        val minutes = (remainingSeconds % 3600L) / 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}m"
            hours > 0L -> "${hours}h"
            minutes > 0L -> "${minutes}m"
            else -> "< 1m"
        }
    }

    private fun claimEntryTag(entry: LedgerEntry): JsonArray =
        if (!entry.relayHint.isNullOrBlank()) {
            tag("claim", entry.claimId, entry.offerId, entry.relayHint)
        } else {
            tag("claim", entry.claimId, entry.offerId)
        }

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
