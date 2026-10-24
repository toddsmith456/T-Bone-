package social.tbone.wallet

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.Nip44
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.toHex
import social.tbone.settings.ZapAmounts

class ZapProtocolTest {
    @Test
    fun normalizesEditableZapPresetsWithoutAcceptingUnsafeValues() {
        assertEquals(listOf(21L, 100L, 500L), ZapAmounts.parse(null))
        assertEquals(
            listOf(1L, 21L, 1_000_000L),
            ZapAmounts.normalize(listOf(1L, 21L, 21L, 0L, -5L, 1_000_000L)),
        )
        assertFalse(ZapAmounts.normalize(listOf(0L, -1L)).contains(0L))
        assertTrue(runCatching { ZapAmounts.requireValid(1_000_001L) }.isFailure)
        assertEquals(listOf(100L, 500L, 21L), ZapAmounts.move(listOf(21L, 100L, 500L), 0, 2))
        assertEquals(listOf(21L, 100L, 500L), ZapAmounts.move(listOf(21L, 100L, 500L), -1, 2))
        assertEquals(listOf(21L), ZapAmounts.remove(listOf(21L), 21L))
        assertEquals(listOf(100L), ZapAmounts.remove(listOf(21L, 100L), 21L))
    }

    @Test
    fun profileLightningAddressPrefersLud16AndSupportsLnurlFallback() {
        assertEquals(
            "alice@example.com",
            ProfileContent(lud16 = " alice@example.com ", lud06 = "LNURL1fallback").lightningAddress,
        )
        assertEquals("LNURL1fallback", ProfileContent(lud06 = " LNURL1fallback ").lightningAddress)
        assertEquals(
            "https://pay.example/.well-known/lnurlp/alice",
            Nip19.lnurlToUrl("https://pay.example/.well-known/lnurlp/alice"),
        )
        assertNull(Nip19.lnurlToUrl("not-a-lightning-address"))
    }

    @Test
    fun nip44ConversationKeyMatchesAmethystVector() {
        val key = Nip44.conversationKey(
            "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268".hexToBytes(),
            "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133".hexToBytes(),
        )
        assertEquals("3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1", key.toHex())
    }

    @Test
    fun nip44MessageKeysMatchAmethystVector() {
        val keys = Nip44.messageKeys(
            "a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54".hexToBytes(),
            "e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72".hexToBytes(),
        )
        assertEquals("f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76", keys.chachaKey.toHex())
        assertEquals("c4ad129bb01180c0933a160c", keys.chachaNonce.toHex())
        assertEquals("027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4", keys.hmacKey.toHex())
    }

    @Test
    fun nip44PayloadDecryptsWithAmethystVector() {
        val plaintext = Nip44.decryptWithConversationKey(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes(),
        )
        assertEquals("a", plaintext)
    }

    @Test
    fun encodesLnurlTagAsBech32AndRoundTrips() {
        val endpoint = "https://pay.example/.well-known/lnurlp/alice"
        val encoded = Nip19.lnurlToBech32(endpoint)
        assertTrue(encoded?.startsWith("lnurl1") == true)
        assertEquals(endpoint, Nip19.lnurlToUrl(encoded!!))
    }

    @Test
    fun rejectsInvalidOrAmountlessBolt11BeforePayment() {
        assertNull(LightningInvoice.amountMsats("not-an-invoice"))
        assertNull(LightningInvoice.amountMsats(fakeInvoice("lnbc")))
        assertEquals(250_000_000L, LightningInvoice.amountMsats(fakeInvoice("lnbc2500u")))
        assertEquals(100_000L, LightningInvoice.amountMsats(fakeInvoice("lnbc1u")))
        assertNull(LightningInvoice.amountMsats(fakeInvoice("lnbc1p")))
    }

    @Test
    fun createsLightningAndMoneroWalletUris() {
        val invoice = "lnbc2500u1qqqqqq"
        assertEquals("lightning:$invoice", WalletPaymentUris.lightningInvoice(invoice))
        val address = validMoneroAddress()
        assertEquals("monero:$address", WalletPaymentUris.moneroAddress(address))
        assertTrue(runCatching { WalletPaymentUris.lightningInvoice(" ") }.isFailure)
    }

    @Test
    fun acceptsMoneroBase58AddressShapesAndRejectsMalformedInput() {
        val address = validMoneroAddress()
        assertEquals(address, MoneroAddressPolicy.normalize("  $address  "))
        assertNull(MoneroAddressPolicy.normalizeOrNull("too-short"))
        assertNull(MoneroAddressPolicy.normalizeOrNull("0" + "1".repeat(94)))
        assertNull(MoneroAddressPolicy.normalizeOrNull("4" + "!".repeat(94)))
    }

    @Test
    fun parsesMoneroAndXmrPaymentTargetsOnly() {
        val monero = event(
            buildJsonArray { add("payto"); add("monero"); add(validMoneroAddress()) },
            buildJsonArray { add("payto"); add("lightning"); add("alice@example.com") },
        )
        assertEquals(validMoneroAddress(), NipA3PaymentTargets.moneroAddress(monero))

        val alias = event(buildJsonArray { add("payto"); add("xmr"); add(validMoneroAddress()) })
        assertEquals(validMoneroAddress(), NipA3PaymentTargets.moneroAddress(alias))

        val otherKind = event(buildJsonArray { add("payto"); add("monero"); add(validMoneroAddress()) }, kind = 1)
        assertNull(NipA3PaymentTargets.moneroAddress(otherKind))

        val malformed = event(buildJsonArray { add("payto"); add("monero"); add("not-an-address") })
        assertNull(NipA3PaymentTargets.moneroAddress(malformed))
        assertTrue(NipA3PaymentTargets.hasMoneroTag(malformed))
    }

    @Test
    fun updatesMoneroTargetWithoutDroppingOtherOrUnknownTags() {
        val pubkey = "ab".repeat(32)
        val bitcoin = buildJsonArray { add("payto"); add("bitcoin"); add("bc1qexample") }
        val unknown = buildJsonArray { add("future-tag"); add("one"); add("two"); add("three") }
        val oldMonero = buildJsonArray { add("payto"); add("xmr"); add(validMoneroAddress()) }
        val existing = event(bitcoin, unknown, oldMonero, pubkey = pubkey)

        val updated = NipA3PaymentTargets.updateMoneroAddress(
            pubkey = pubkey,
            existing = existing,
            address = "8" + "2".repeat(94),
            createdAt = existing.createdAt + 1,
        )

        assertEquals(EventKind.PAYMENT_TARGETS, updated.kind)
        assertEquals(pubkey, updated.pubkey)
        assertEquals(listOf(bitcoin, unknown), updated.tags.take(2))
        assertEquals(
            listOf("payto", "monero", "8" + "2".repeat(94)),
            updated.tags.last().map { it.toString().trim('"') },
        )
    }

    @Test
    fun removingMoneroTargetPreservesAllNonMoneroTags() {
        val pubkey = "cd".repeat(32)
        val bitcoin = buildJsonArray { add("payto"); add("bitcoin"); add("bc1qother") }
        val unknown = buildJsonArray { add("future-tag"); add("opaque") }
        val monero = buildJsonArray { add("payto"); add("monero"); add(validMoneroAddress()) }
        val malformedAlias = buildJsonArray { add("payto"); add("xmr"); add("not-valid") }
        val existing = event(bitcoin, unknown, monero, malformedAlias, pubkey = pubkey)

        val updated = NipA3PaymentTargets.updateMoneroAddress(pubkey, existing, null, existing.createdAt + 1)
        assertEquals(listOf(bitcoin, unknown), updated.tags)
    }

    private fun event(
        vararg tags: kotlinx.serialization.json.JsonArray,
        pubkey: String = "ab".repeat(32),
        kind: Int = EventKind.PAYMENT_TARGETS,
    ) = Event(
        id = "01".repeat(32),
        pubkey = pubkey,
        createdAt = 1_760_000_000,
        kind = kind,
        tags = tags.toList(),
        content = "",
        sig = "02".repeat(64),
    )

    private fun validMoneroAddress() = "4" + "1".repeat(94)

    private fun fakeInvoice(hrp: String): String {
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = listOf(0, 1, 2, 3, 4, 5)
        fun polymod(values: List<Int>): Int {
            val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b)
            var checksum = 1
            for (value in values) {
                val top = checksum ushr 25
                checksum = ((checksum and 0x1ffffff) shl 5) xor value
                for (i in generator.indices) if (((top ushr i) and 1) != 0) checksum = checksum xor generator[i]
            }
            return checksum
        }
        val expanded = hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }
        val checksum = polymod(expanded + data + List(6) { 0 }) xor 1
        val check = (0 until 6).map { (checksum ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + check).joinToString("") { charset[it].toString() }
    }
}
