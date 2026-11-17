package social.tbone.nostr

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTokensProtocolTest {

    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)
    private val charlie = "c".repeat(64)
    private val baseTime = 1_800_000_000L

    private fun finalizeMined(unsigned: UnsignedEvent, powBits: Int = 8): Event = runBlocking {
        val mined = Nip13.mine(unsigned, powBits)
        Event(
            id = mined.computeId(),
            pubkey = mined.pubkey,
            createdAt = mined.createdAt,
            kind = mined.kind,
            tags = mined.tags,
            content = mined.content,
            sig = "0".repeat(128),
        )
    }

    private fun finalizeNoPow(unsigned: UnsignedEvent): Event = Event(
        id = unsigned.computeId(),
        pubkey = unsigned.pubkey,
        createdAt = unsigned.createdAt,
        kind = unsigned.kind,
        tags = unsigned.tags,
        content = unsigned.content,
        sig = "0".repeat(128),
    )

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    @Test
    fun `slug validation allows clean lowercase tokens and rejects invalid slugs`() {
        assertTrue(DailyTokens.isValidSlug("steak"))
        assertTrue(DailyTokens.isValidSlug("t-bone-1"))
        assertFalse(DailyTokens.isValidSlug(""))
        assertFalse(DailyTokens.isValidSlug("Steak"))
        assertFalse(DailyTokens.isValidSlug("-steak"))
        assertFalse(DailyTokens.isValidSlug("steak:shard:1"))
        assertFalse(DailyTokens.isValidSlug("a".repeat(33)))
    }

    @Test
    fun `offer parser rejects non-empty content, self-sends, NIP-40 expiration, and future skew`() {
        val validOffer = finalizeMined(
            DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime),
            powBits = 8,
        )
        assertNotNull(DailyTokens.parseOffer(validOffer, baseTime + 10, requireSig = false, minPowBits = 8))

        // Non-empty content rejected (silent token rule)
        val noisy = finalizeMined(
            UnsignedEvent(alice, baseTime, EventKind.TOKEN_OFFER, validOffer.tags.filterNot { (it[0] as JsonPrimitive).content == "nonce" }, "hi"),
            powBits = 8,
        )
        assertNull(DailyTokens.parseOffer(noisy, baseTime + 10, requireSig = false, minPowBits = 8))

        // Self-send rejected
        val selfOffer = finalizeMined(
            UnsignedEvent(alice, baseTime, EventKind.TOKEN_OFFER, listOf(tag("t", "steak"), tag("p", alice)), ""),
            powBits = 8,
        )
        assertNull(DailyTokens.parseOffer(selfOffer, baseTime + 10, requireSig = false, minPowBits = 8))

        // NIP-40 expiration tag forbidden on Token Offer
        val expiringOffer = finalizeMined(
            UnsignedEvent(
                alice,
                baseTime,
                EventKind.TOKEN_OFFER,
                listOf(tag("t", "steak"), tag("p", bob), tag("expiration", (baseTime + 86_400L).toString())),
                "",
            ),
            powBits = 8,
        )
        assertNull(DailyTokens.parseOffer(expiringOffer, baseTime + 10, requireSig = false, minPowBits = 8))

        // Future timestamp beyond 300s rejected
        assertNull(DailyTokens.parseOffer(validOffer, baseTime - 301L, requireSig = false, minPowBits = 8))
    }

    @Test
    fun `claim builder clamps minor negative clock skew and rejects expired offers`() {
        val offerEvent = finalizeMined(
            DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime),
            powBits = 8,
        )
        val offer = requireNotNull(DailyTokens.parseOffer(offerEvent, baseTime, requireSig = false, minPowBits = 8))

        // Device clock 20s behind sender clamps cleanly to offer.offeredAt
        val clamped = DailyTokens.buildUnsignedClaim(bob, offer, nowSeconds = baseTime - 20L).getOrThrow()
        assertEquals(baseTime, clamped.createdAt)

        // Exact 24-hour boundary succeeds
        val atEdge = DailyTokens.buildUnsignedClaim(bob, offer, nowSeconds = baseTime + 86_400L).getOrThrow()
        assertEquals(baseTime + 86_400L, atEdge.createdAt)

        // 1 second past 24 hours fails
        assertTrue(DailyTokens.buildUnsignedClaim(bob, offer, nowSeconds = baseTime + 86_401L).isFailure)
    }

    @Test
    fun `end-to-end verification enforces 24h claim window, dedupes duplicates, and rejects spoofed ledgers`() {
        val offerEvent = finalizeMined(
            DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime),
            powBits = 8,
        )
        val offer = requireNotNull(DailyTokens.parseOffer(offerEvent, baseTime, requireSig = false, minPowBits = 8))

        // Valid claim at +3600s
        val claimEvent = finalizeMined(
            DailyTokens.buildUnsignedClaim(bob, offer, nowSeconds = baseTime + 3_600L).getOrThrow(),
            powBits = 8,
        )
        val claim = requireNotNull(DailyTokens.parseClaim(claimEvent, requireSig = false, minPowBits = 8))

        // Late claim (after 24h) crafted manually must NOT verify
        val lateOfferEvent = finalizeMined(
            DailyTokens.buildUnsignedOffer(charlie, bob, DailyTokens.STEAK_SLUG, baseTime),
            powBits = 8,
        )
        val lateOffer = requireNotNull(DailyTokens.parseOffer(lateOfferEvent, baseTime, requireSig = false, minPowBits = 8))
        val lateClaimEvent = finalizeMined(
            UnsignedEvent(
                pubkey = bob,
                createdAt = baseTime + 86_401L,
                kind = EventKind.TOKEN_CLAIM,
                tags = listOf(tag("t", "steak"), tag("e", lateOffer.offerId), tag("p", charlie)),
                content = "",
            ),
            powBits = 8,
        )
        val lateClaim = requireNotNull(DailyTokens.parseClaim(lateClaimEvent, requireSig = false, minPowBits = 8))

        // Bob's ledger lists: valid pair TWICE + expired late pair + totally fake random hex IDs
        val rawLedger = finalizeNoPow(
            UnsignedEvent(
                pubkey = bob,
                createdAt = baseTime + 90_000L,
                kind = EventKind.TOKEN_LEDGER,
                tags = listOf(
                    tag("d", "steak"),
                    tag("claim", claim.claimId, offer.offerId),
                    tag("claim", claim.claimId, offer.offerId), // duplicate
                    tag("claim", lateClaim.claimId, lateOffer.offerId), // expired (>24h)
                    tag("claim", "f".repeat(64), "e".repeat(64)), // spoofed non-existent pair
                ),
                content = "",
            ),
        )
        val ledger = requireNotNull(DailyTokens.parseLedger(rawLedger, requireSig = false))

        val verified = DailyTokens.verifyProfileTokens(
            recipientPubkey = bob,
            slug = DailyTokens.STEAK_SLUG,
            primaryLedger = ledger,
            offersById = mapOf(offer.offerId to offer, lateOffer.offerId to lateOffer),
            claimsById = mapOf(claim.claimId to claim, lateClaim.claimId to lateClaim),
        )

        assertEquals(1, verified.size)
        assertEquals(offer.offerId, verified.first().offerId)
        assertEquals(alice, verified.first().senderPubkey)
    }

    @Test
    fun `rolling 24h per-recipient cooldown allows unlimited distinct recipients while blocking same-recipient repeats`() {
        // Alice sends Offer 1 to Bob at T (unclaimed)
        val oBob1 = requireNotNull(
            DailyTokens.parseOffer(
                finalizeMined(DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime), 8),
                baseTime + 100_000L,
                requireSig = false,
                minPowBits = 8,
            ),
        )
        // Alice ALSO sends to Charlie at the EXACT same second T — allowed because Charlie is a different person!
        val oCharlie1 = requireNotNull(
            DailyTokens.parseOffer(
                finalizeMined(DailyTokens.buildUnsignedOffer(alice, charlie, DailyTokens.STEAK_SLUG, baseTime), 8),
                baseTime + 100_000L,
                requireSig = false,
                minPowBits = 8,
            ),
        )
        // Alice tries to send a second steak to Bob at T + 86399s (1 second too early for Bob!)
        val oBobTooEarly = requireNotNull(
            DailyTokens.parseOffer(
                finalizeMined(DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime + 86_399L), 8),
                baseTime + 100_000L,
                requireSig = false,
                minPowBits = 8,
            ),
        )
        // Alice sends a valid second steak to Bob at T + 86400s (exact rolling 24h cooldown elapsed for Bob!)
        val oBob2 = requireNotNull(
            DailyTokens.parseOffer(
                finalizeMined(DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, baseTime + 86_400L), 8),
                baseTime + 100_000L,
                requireSig = false,
                minPowBits = 8,
            ),
        )

        val validOfferIds = DailyTokens.resolveValidOfferIds(listOf(oBob2, oBobTooEarly, oCharlie1, oBob1))
        assertTrue("First offer to Bob is valid", oBob1.offerId in validOfferIds)
        assertTrue("Simultaneous offer to Charlie is valid", oCharlie1.offerId in validOfferIds)
        assertFalse("Second offer to Bob within 86400s is rejected", oBobTooEarly.offerId in validOfferIds)
        assertTrue("Second offer to Bob after 86400s is valid", oBob2.offerId in validOfferIds)

        // Check per-recipient cooldown helper right after sending only to Bob at baseTime:
        assertEquals(
            baseTime + 86_400L,
            DailyTokens.cooldownUntilForRecipient(alice, bob, DailyTokens.STEAK_SLUG, listOf(oBob1), baseTime + 100L),
        )
        assertEquals(
            0L,
            DailyTokens.cooldownUntilForRecipient(alice, charlie, DailyTokens.STEAK_SLUG, listOf(oBob1), baseTime + 100L),
        )
        val map = DailyTokens.cooldownUntilByRecipient(alice, DailyTokens.STEAK_SLUG, listOf(oBob1), baseTime + 100L)
        assertEquals(mapOf(bob to (baseTime + 86_400L)), map)

        // Bob tries to claim oBobTooEarly — extraSenderOffers exposes unclaimed oBob1 to Bob, disqualifying oBobTooEarly
        val cTooEarly = requireNotNull(
            DailyTokens.parseClaim(
                finalizeMined(DailyTokens.buildUnsignedClaim(bob, oBobTooEarly, baseTime + 86_450L).getOrThrow(), 8),
                requireSig = false,
                minPowBits = 8,
            ),
        )
        val cBob2 = requireNotNull(
            DailyTokens.parseClaim(
                finalizeMined(DailyTokens.buildUnsignedClaim(bob, oBob2, baseTime + 86_460L).getOrThrow(), 8),
                requireSig = false,
                minPowBits = 8,
            ),
        )
        val ledger = requireNotNull(
            DailyTokens.parseLedger(
                finalizeNoPow(
                    DailyTokens.buildUnsignedLedger(
                        ownerPubkey = bob,
                        slug = DailyTokens.STEAK_SLUG,
                        entries = listOf(
                            DailyTokens.LedgerEntry(cTooEarly.claimId, oBobTooEarly.offerId),
                            DailyTokens.LedgerEntry(cBob2.claimId, oBob2.offerId),
                        ),
                        createdAt = baseTime + 86_500L,
                    ),
                ),
                requireSig = false,
            ),
        )

        val verified = DailyTokens.verifyProfileTokens(
            recipientPubkey = bob,
            slug = DailyTokens.STEAK_SLUG,
            primaryLedger = ledger,
            offersById = mapOf(oBobTooEarly.offerId to oBobTooEarly, oBob2.offerId to oBob2),
            claimsById = mapOf(cTooEarly.claimId to cTooEarly, cBob2.claimId to cBob2),
            extraSenderOffers = listOf(oBob1, oCharlie1),
        )

        assertEquals(1, verified.size)
        assertEquals(oBob2.offerId, verified.first().offerId)
    }

    @Test
    fun `ledger overflow creates archive shard and verifies union across primary plus shard`() {
        val entries = (1..3).map { idx ->
            val offerTime = baseTime + idx * 90_000L
            val o = requireNotNull(
                DailyTokens.parseOffer(
                    finalizeMined(DailyTokens.buildUnsignedOffer(alice, bob, DailyTokens.STEAK_SLUG, offerTime), 4),
                    offerTime + 100L,
                    requireSig = false,
                    minPowBits = 4,
                ),
            )
            val c = requireNotNull(
                DailyTokens.parseClaim(
                    finalizeMined(DailyTokens.buildUnsignedClaim(bob, o, offerTime + 10L).getOrThrow(), 4),
                    requireSig = false,
                    minPowBits = 4,
                ),
            )
            Triple(o, c, DailyTokens.LedgerEntry(c.claimId, o.offerId))
        }

        // Force shard overflow with maxPerLedger = 2
        val initialLedger = requireNotNull(
            DailyTokens.parseLedger(
                finalizeNoPow(
                    DailyTokens.buildUnsignedLedger(
                        ownerPubkey = bob,
                        slug = DailyTokens.STEAK_SLUG,
                        entries = listOf(entries[0].third, entries[1].third),
                        createdAt = baseTime + 200_000L,
                    ),
                ),
                requireSig = false,
            ),
        )

        val plan = DailyTokens.planLedgerAppend(
            ownerPubkey = bob,
            slug = DailyTokens.STEAK_SLUG,
            existingPrimary = initialLedger,
            newEntry = entries[2].third,
            maxPerLedger = 2,
        )

        assertNotNull(plan.newShard)
        assertEquals(1, plan.newShard!!.first)
        assertEquals(2, plan.newShard!!.second.size)
        assertEquals(1, plan.primaryEntries.size)
        assertEquals(1, plan.shardAddresses.size)

        val shardEvent = finalizeNoPow(
            DailyTokens.buildUnsignedShard(
                ownerPubkey = bob,
                slug = DailyTokens.STEAK_SLUG,
                shardIndex = plan.newShard!!.first,
                entries = plan.newShard!!.second,
                createdAt = baseTime + 300_000L,
            ),
        )
        val primaryEvent = finalizeNoPow(
            DailyTokens.buildUnsignedLedger(
                ownerPubkey = bob,
                slug = DailyTokens.STEAK_SLUG,
                entries = plan.primaryEntries,
                shardAddresses = plan.shardAddresses,
                createdAt = baseTime + 300_001L,
            ),
        )

        val parsedShard = requireNotNull(DailyTokens.parseLedger(shardEvent, requireSig = false))
        val parsedPrimary = requireNotNull(DailyTokens.parseLedger(primaryEvent, requireSig = false))
        val shardAddr = DailyTokens.shardAddress(bob, DailyTokens.STEAK_SLUG, 1)

        val verified = DailyTokens.verifyProfileTokens(
            recipientPubkey = bob,
            slug = DailyTokens.STEAK_SLUG,
            primaryLedger = parsedPrimary,
            shardLedgers = mapOf(shardAddr to parsedShard),
            offersById = entries.associate { it.first.offerId to it.first },
            claimsById = entries.associate { it.second.claimId to it.second },
        )

        assertEquals(3, verified.size)
    }
}
