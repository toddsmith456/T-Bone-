package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import social.tbone.nostr.geohash.GeohashKeyDerivation
import java.security.SecureRandom

/**
 * Characterises the event verification path that a cold start depends on.
 *
 * Cold start restores a few hundred notes from Room and re-authenticates them.
 * What that costs is `verifyId()` — the canonical re-serialisation plus SHA-256 —
 * which every restored instance pays once. Signature results are cached by id in
 * [VerifiedEventCache], so the expensive curve maths is not repeated per instance.
 *
 * The tests below are the guarantees that make any future optimisation of that
 * path safe. An earlier attempt to skip the id hashing for cached ids was
 * rejected precisely because of these: see
 * `a distinct payload reusing a cached id must not be trusted`.
 */
class EventVerifiedIdCacheTest {

    @Before
    fun resetCache() = VerifiedEventCache.clear()

    private fun key(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun sign(privkey: ByteArray, content: String, kind: Int = 1, tags: List<JsonArray> = emptyList()): Event {
        val pubkey = GeohashKeyDerivation.pubkeyBytesFromPrivkey(privkey).toHex()
        val unsigned = UnsignedEvent(pubkey = pubkey, createdAt = 1_700_000_000L, kind = kind, tags = tags, content = content)
        val id = unsigned.computeId()
        val sig = GeohashKeyDerivation.schnorrSign(id.hexToBytes(), privkey)
        return Event(id = id, pubkey = pubkey, createdAt = 1_700_000_000L, kind = kind, tags = tags, content = content, sig = sig)
    }

    @Test
    fun `a valid event verifies and is remembered`() {
        val event = sign(key(), "cold start")
        assertTrue(event.verify())
        assertTrue("the id must be recorded once verified", VerifiedEventCache.contains(event.id))
        assertTrue("verification must be idempotent", event.verify())
        assertEquals("exactly one entry for one event", 1, VerifiedEventCache.size())
    }

    @Test
    fun `a distinct payload reusing a cached id must not be trusted`() {
        // This is the exact case that rules out skipping verifyId() for cached ids:
        // a restored row plus a mutated payload would otherwise be trusted forever.
        val event = sign(key(), "original")
        assertTrue(event.verify())
        assertTrue(VerifiedEventCache.contains(event.id))

        val tampered = event.copy(content = "tampered")
        assertNotEquals(
            "the payload changed, so its recomputed id cannot match the borrowed one",
            event.id,
            UnsignedEvent(tampered.pubkey, tampered.createdAt, tampered.kind, tampered.tags, tampered.content).computeId(),
        )
        assertFalse("a borrowed id must not authenticate altered content", tampered.verify())
    }

    @Test
    fun `an invalid signature is never remembered`() {
        val event = sign(key(), "fail me")
        val broken = event.copy(sig = "00".repeat(64))
        assertFalse(broken.verify())
        assertFalse(broken.verify())
        assertFalse(
            "a failed verification must never be recorded as good",
            VerifiedEventCache.contains(broken.id),
        )
    }

    @Test
    fun `restored instances pay one signature and no id recomputation is cached wrongly`() {
        val event = sign(key(), "restored")
        assertTrue(event.verify())

        // Standing in for the Room round-trip: a new instance of the same payload.
        val restored = event.copy()
        assertTrue(restored.verify())

        // A restored instance whose payload does not match its id still fails,
        // regardless of what the cache knows.
        val corrupted = restored.copy(createdAt = 1L)
        assertFalse(corrupted.verify())
    }

    @Test
    fun `tags survive a restore and do not affect verification`() {
        val tags = listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive("f".repeat(64)))))
        val event = sign(key(), "with tags", tags = tags)
        assertTrue(event.verify())
        assertTrue(event.parsedTags.any { it.name == "p" })
        assertFalse("parsed tags must not be reused across distinct instances wrongly", event.copy(tags = emptyList()).verify())
    }
}
