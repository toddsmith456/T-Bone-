package social.tbone.nostr

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.account.signer.ClientEventTag

class Nip13PowTest {

    private val pubkey = "1".repeat(64)

    @Test
    fun `leadingZeroBits counts exact leading zeros across hex nibbles`() {
        assertEquals(0, Nip13.leadingZeroBits("f" + "0".repeat(63)))
        assertEquals(1, Nip13.leadingZeroBits("7" + "f".repeat(63)))
        assertEquals(2, Nip13.leadingZeroBits("3" + "f".repeat(63)))
        assertEquals(3, Nip13.leadingZeroBits("1" + "f".repeat(63)))
        assertEquals(4, Nip13.leadingZeroBits("0f" + "0".repeat(62)))
        assertEquals(16, Nip13.leadingZeroBits("00008" + "a".repeat(59)))
        assertEquals(19, Nip13.leadingZeroBits("00001" + "a".repeat(59)))
        assertEquals(20, Nip13.leadingZeroBits("00000f" + "a".repeat(58)))
        assertEquals(0, Nip13.leadingZeroBits("0000")) // malformed length
    }

    @Test
    fun `hasValidPow requires both actual zero bits and committed target difficulty`() {
        val strongId = "00001" + "a".repeat(59) // 19 leading zero bits
        val valid = Event(
            id = strongId,
            pubkey = pubkey,
            createdAt = 1_800_000_000L,
            kind = EventKind.TOKEN_OFFER,
            tags = listOf(tag("nonce", "42", "16")),
            content = "",
            sig = "0".repeat(128),
        )
        assertTrue(Nip13.hasValidPow(valid, 16))
        assertFalse(Nip13.hasValidPow(valid, 17)) // committed target is only 16

        // Lucky hash with lower committed target must be rejected per NIP-13.
        val luckySpam = valid.copy(tags = listOf(tag("nonce", "42", "8")))
        assertFalse(Nip13.hasValidPow(luckySpam, 16))

        // Missing nonce tag must be rejected.
        val noNonce = valid.copy(tags = emptyList())
        assertFalse(Nip13.hasValidPow(noNonce, 16))
    }

    @Test
    fun `mine produces an event meeting 16-bit PoW and survives ClientEventTag decoration`() = runBlocking {
        val unsigned = DailyTokens.buildUnsignedOffer(
            senderPubkey = pubkey,
            recipientPubkey = "2".repeat(64),
            slug = DailyTokens.STEAK_SLUG,
            nowSeconds = 1_800_000_000L,
        )

        val mined = Nip13.mine(unsigned, targetBits = DailyTokens.MIN_POW_BITS)
        val id = mined.computeId()
        assertTrue("Expected >=16 zero bits, got ${Nip13.leadingZeroBits(id)}", Nip13.leadingZeroBits(id) >= 16)

        // Decorating via ClientTaggingSigner must preserve the exact mined event & id.
        val decorated = ClientEventTag.decorate(mined, enabled = true)
        assertEquals(id, decorated.computeId())

        val event = Event(
            id = id,
            pubkey = mined.pubkey,
            createdAt = mined.createdAt,
            kind = mined.kind,
            tags = mined.tags,
            content = mined.content,
            sig = "0".repeat(128),
        )
        assertTrue(Nip13.hasValidPow(event, DailyTokens.MIN_POW_BITS))
        assertEquals(16, Nip13.committedTargetDifficulty(event))
    }

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
