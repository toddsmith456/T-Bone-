package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventCryptoPerformanceTest {

    @Test
    fun fastToHexMatchesFormatForAllByteValues() {
        val allBytes = ByteArray(256) { it.toByte() }
        val expected = allBytes.joinToString("") { "%02x".format(it) }
        assertEquals(expected, allBytes.toHex())
        assertArrayEquals(allBytes, allBytes.toHex().hexToBytes())
    }

    @Test
    fun serializeForIdMatchesBuildJsonArrayByteForByte() {
        val pubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val tags = listOf(
            buildJsonArray { add("e"); add("abcd1234"); add("wss://relay.example.com"); add("reply") },
            buildJsonArray { add("p"); add(pubkey); add("quote \"here\" \\ path") },
        )
        val contents = listOf(
            "hello world",
            "quotes \"inside\" and \\backslashes\\\nand\ttabs\r\nplus emoji 🚀🔥",
            "rare control chars \u0000 \u0001 \u001F end",
            "",
        )

        for (content in contents) {
            val fast = Event.serializeForId(pubkey, 1730000000L, EventKind.TEXT_NOTE, tags, content)
            val reference = buildJsonArray {
                add(NostrJson.encodeToJsonElement(0))
                add(NostrJson.encodeToJsonElement(pubkey))
                add(NostrJson.encodeToJsonElement(1730000000L))
                add(NostrJson.encodeToJsonElement(EventKind.TEXT_NOTE))
                add(JsonArray(tags.map { it as JsonElement }))
                add(NostrJson.encodeToJsonElement(content))
            }.toString().toByteArray(Charsets.UTF_8)
            assertArrayEquals("Mismatch for content=$content", reference, fast)
        }
    }

    @Test
    fun verifySchnorrAcceptsOfficialBip340VectorsAndRejectsTampering() {
        // BIP-340 test vector #0
        val pubkey0 = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9".hexToBytes()
        val msg0 = "0000000000000000000000000000000000000000000000000000000000000000".hexToBytes()
        val sig0 = "e907831f80848d1069a5371b402410364bdf1c5f8307b0084c55f1ce2dca821525f66a4a85ea8b71e482a74f382d2ce5ebeee8fdb2172f477df4900d310536c0".hexToBytes()

        assertTrue(Crypto.verifySchnorr(msg0, sig0, pubkey0))
        assertTrue(Crypto.verifySchnorrBouncyCastle(msg0, sig0, pubkey0))

        // BIP-340 test vector #1
        val pubkey1 = "dff1d77f2a671c5f36183726db2341be58feae1da2deced843240f7b502ba659".hexToBytes()
        val msg1 = "243f6a8885a308d313198a2e03707344a4093822299f31d0082efa98ec4e6c89".hexToBytes()
        val sig1 = "6896bd60eeae296db48a229ff71dfe071bde413e6d43f917dc8dcf8c78de33418906d11ac976abccb20b091292bff4ea897efcb639ea871cfa95f6de339e4b0a".hexToBytes()

        assertTrue(Crypto.verifySchnorr(msg1, sig1, pubkey1))
        assertTrue(Crypto.verifySchnorrBouncyCastle(msg1, sig1, pubkey1))

        // Flip 1 bit in message -> must fail
        val tamperedMsg = msg1.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(Crypto.verifySchnorr(tamperedMsg, sig1, pubkey1))
        assertFalse(Crypto.verifySchnorrBouncyCastle(tamperedMsg, sig1, pubkey1))

        // Flip 1 bit in signature -> must fail
        val tamperedSig = sig1.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }
        assertFalse(Crypto.verifySchnorr(msg1, tamperedSig, pubkey1))
        assertFalse(Crypto.verifySchnorrBouncyCastle(msg1, tamperedSig, pubkey1))
    }

    @Test
    fun verifiedEventCacheNeverAllowsTamperedPayloadReusingSameId() {
        val pubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val unsigned = UnsignedEvent(pubkey = pubkey, createdAt = 1730000000L, kind = 1, content = "legit")
        val id = unsigned.computeId()
        VerifiedEventCache.add(id)

        // Distinct Event claiming the cached id with altered content must fail verifyId().
        val spoofed = Event(
            id = id,
            pubkey = pubkey,
            createdAt = 1730000000L,
            kind = 1,
            tags = emptyList(),
            content = "tampered",
            sig = "00".repeat(64),
        )
        assertFalse(spoofed.verify())
        // Repeat check on the same failed instance remains false.
        assertFalse(spoofed.verify())
    }

    @Test
    fun malformedSignatureLengthReturnsFalseCleanly() {
        val pubkey = "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9"
        val unsigned = UnsignedEvent(pubkey = pubkey, createdAt = 1730000001L, kind = 1, content = "short-sig")
        val id = unsigned.computeId()

        val shortSig = Event(
            id = id,
            pubkey = pubkey,
            createdAt = 1730000001L,
            kind = 1,
            tags = emptyList(),
            content = "short-sig",
            sig = "deadbeef",
        )
        assertFalse(shortSig.verify())

        val nonHexSig = shortSig.copy(sig = "z".repeat(128))
        assertFalse(nonHexSig.verify())

        val badPubkey = shortSig.copy(pubkey = "not-hex", sig = "00".repeat(64))
        assertFalse(badPubkey.verify())
    }
}
