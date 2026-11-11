package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Zap receipt decoding. The receipt is authored by the recipient's Lightning
 * node, so everything about the *sender* comes from the embedded zap request —
 * getting that wrong attributes zaps to the wrong person or shows the wrong
 * amount, both of which are user-visible.
 */
class Nip57Test {

    private val recipient = "11".repeat(32)
    private val sender = "22".repeat(32)
    private val noteId = "e1".repeat(32)

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun request(
        author: String = sender,
        pTag: String = recipient,
        content: String = "gm",
    ) = """{"id":"${"a".repeat(64)}","pubkey":"$author","created_at":1,"kind":9734,""" +
        """"tags":[["p","$pTag"]],"content":"$content","sig":"${"0".repeat(128)}"}"""

    /** The author is the recipient's Lightning node, which is the whole point. */
    private fun receipt(tags: List<JsonArray>): Event = Event(
        id = "9".repeat(64),
        pubkey = "44".repeat(32),
        createdAt = 100,
        kind = EventKind.ZAP_RECEIPT,
        tags = tags,
        content = "",
        sig = "0".repeat(128),
    )

    @Test fun readsRecipientSenderAmountAndComment() {
        val parsed = Nip57.receipt(
            receipt(
                listOf(
                    tag("p", recipient),
                    tag("P", sender),
                    tag("e", noteId),
                    tag("amount", "21000"),
                    tag("description", request()),
                ),
            ),
        )!!
        assertEquals(recipient, parsed.recipient)
        assertEquals(sender, parsed.sender)
        assertEquals(21_000L, parsed.amountMsats)
        assertEquals(21L, parsed.amountSats)
        assertEquals("gm", parsed.comment)
        assertEquals(noteId, parsed.zappedEventId)
    }

    @Test fun senderFallsBackToTheZapRequestAuthorWhenPIsMissing() {
        val parsed = Nip57.receipt(
            receipt(listOf(tag("p", recipient), tag("description", request()))),
        )!!
        assertEquals(sender, parsed.sender)
    }

    @Test fun amountFallsBackToTheBolt11Invoice() {
        val parsed = Nip57.receipt(
            receipt(
                listOf(
                    tag("p", recipient),
                    // 250 uBTC = 25,000 sats = 25,000,000 msats
                    tag("bolt11", "lnbc250u1p3xyzabc"),
                ),
            ),
        )!!
        assertEquals(25_000_000L, parsed.amountMsats)
        assertEquals(25_000L, parsed.amountSats)
    }

    @Test fun malformedDescriptionStillYieldsAReceipt() {
        val parsed = Nip57.receipt(
            receipt(listOf(tag("p", recipient), tag("P", sender), tag("description", "not json"))),
        )!!
        // The amount and the sender are still known; only the comment is lost.
        assertEquals(sender, parsed.sender)
        assertEquals("", parsed.comment)
    }

    @Test fun receiptWithoutRecipientIsRejected() {
        assertNull(Nip57.receipt(receipt(listOf(tag("amount", "1000")))))
    }

    @Test fun nonReceiptKindIsRejected() {
        val notAReceipt = Event(
            id = "9".repeat(64),
            pubkey = "44".repeat(32),
            createdAt = 100,
            kind = EventKind.ZAP_REQUEST,
            tags = listOf(tag("p", recipient)),
            content = "",
            sig = "0".repeat(128),
        )
        assertNull(Nip57.receipt(notAReceipt))
    }

    @Test fun bolt11UnitsConvertToMsats() {
        // 2500 µBTC = 250,000 sats = 250,000,000 msats
        assertEquals(250_000_000L, LightningAmount.fromBolt11("lnbc2500u1pvjluezpp5qqq"))
        // 1 mBTC = 100,000,000 msats
        assertEquals(100_000_000L, LightningAmount.fromBolt11("lnbc1m1pvjluezpp5qqq"))
        // 1 nBTC = 100 msats
        assertEquals(100L, LightningAmount.fromBolt11("lnbc1n1pvjluezpp5qqq"))
        // pico-BTC rounds down to whole msats rather than producing a fraction.
        assertEquals(10L, LightningAmount.fromBolt11("lnbc100p1pvjluezpp5qqq"))
    }

    @Test fun amountlessInvoiceYieldsNull() {
        // No amount segment before the separator: nothing to display.
        assertNull(LightningAmount.fromBolt11("lnbc1pvjluezpp5qqq"))
    }
}
