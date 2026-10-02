package social.tbone.media.blossom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.account.signer.NostrSigner
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import java.util.Base64

/**
 * Locks down the BUD-11 authorization token — the piece that was wrong before
 * and made every upload fail.
 *
 * These assertions mirror what was verified live against nostr.download,
 * blossom.data.haus, blossom.ditto.pub, blossom.nostr.build, blossom.band and
 * blossom.primal.net while rebuilding the uploader.
 */
class BlossomAuthTest {

    /** Minimal signer: computes the real event id, uses a placeholder sig. */
    private class FakeSigner(override val pubkey: String = "a".repeat(64)) : NostrSigner {
        override suspend fun signEvent(event: UnsignedEvent): Result<Event> = Result.success(
            Event(
                id = event.computeId(),
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                kind = event.kind,
                tags = event.tags,
                content = event.content,
                sig = "b".repeat(128),
            ),
        )

        override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String) =
            Result.success(plaintext)

        override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String) =
            Result.success(ciphertext)
    }

    private fun tagOf(event: Event, name: String): List<String>? =
        event.tags.firstOrNull { (it.firstOrNull() as? JsonPrimitive)?.content == name }
            ?.mapNotNull { (it as? JsonPrimitive)?.content }

    private fun tagsNamed(event: Event, name: String): List<List<String>> =
        event.tags
            .filter { (it.firstOrNull() as? JsonPrimitive)?.content == name }
            .map { array -> array.mapNotNull { (it as? JsonPrimitive)?.content } }

    @Test
    fun `upload token is kind 24242 not NIP-98 27235`() = runBlocking {
        val event = BlossomAuth.sign(
            signer = FakeSigner(),
            type = BlossomAuth.TYPE_UPLOAD,
            alt = "Uploading image",
            hash = "ab".repeat(32),
            size = 1234,
            servers = listOf("https://nostr.download"),
        )
        // The whole bug in one assertion: Blossom only accepts 24242.
        assertEquals(24242, event.kind)
        assertEquals(24242, BlossomAuth.KIND_AUTHORIZATION)
    }

    @Test
    fun `upload token carries the tags servers validate`() = runBlocking {
        val hash = "ab".repeat(32)
        val now = 1_700_000_000L
        val event = BlossomAuth.sign(
            signer = FakeSigner(),
            type = BlossomAuth.TYPE_UPLOAD,
            alt = "Uploading image",
            hash = hash,
            size = 184_292,
            servers = listOf("https://cdn.example.com"),
            createdAt = now,
            ttlSeconds = 3600,
        )

        assertEquals(listOf("t", "upload"), tagOf(event, "t"))
        assertEquals(listOf("x", hash), tagOf(event, "x"))
        assertEquals(listOf("size", "184292"), tagOf(event, "size"))
        assertEquals(listOf("expiration", (now + 3600).toString()), tagOf(event, "expiration"))
        assertEquals(listOf("server", "cdn.example.com"), tagOf(event, "server"))
        // `alt`/content is the human-readable purpose, not empty.
        assertEquals("Uploading image", event.content)
    }

    @Test
    fun `expiration is in the future`() = runBlocking {
        val event = BlossomAuth.sign(FakeSigner(), BlossomAuth.TYPE_UPLOAD, "Uploading image")
        val expires = tagOf(event, "expiration")!![1].toLong()
        assertTrue("expiration must be after created_at", expires > event.createdAt)
    }

    @Test
    fun `hash is lowercased so it matches the X-SHA-256 header and blob url`() = runBlocking {
        val event = BlossomAuth.sign(
            FakeSigner(),
            BlossomAuth.TYPE_UPLOAD,
            "Uploading image",
            hash = "AB".repeat(32),
        )
        assertEquals("ab".repeat(32), tagOf(event, "x")!![1])
    }

    @Test
    fun `server tag holds the bare lowercase domain with no scheme or port`() = runBlocking {
        val event = BlossomAuth.sign(
            FakeSigner(),
            BlossomAuth.TYPE_UPLOAD,
            "Uploading image",
            servers = listOf("https://Blossom.Example.COM:443", "http://other.example"),
        )
        val servers = tagsNamed(event, "server").map { it[1] }
        assertEquals(listOf("blossom.example.com", "other.example"), servers)
    }

    @Test
    fun `one token can be scoped to the whole pool and never repeats a host`() = runBlocking {
        val event = BlossomAuth.sign(
            FakeSigner(),
            BlossomAuth.TYPE_UPLOAD,
            "Uploading image",
            servers = listOf(
                "https://nostr.download",
                "https://blossom.data.haus",
                "https://nostr.download/", // duplicate, different trailing slash
            ),
        )
        assertEquals(
            listOf("nostr.download", "blossom.data.haus"),
            tagsNamed(event, "server").map { it[1] },
        )
    }

    @Test
    fun `x and size tags are omitted for operations that do not need them`() = runBlocking {
        val event = BlossomAuth.sign(FakeSigner(), BlossomAuth.TYPE_GET, "Reading blob")
        assertEquals(listOf("t", "get"), tagOf(event, "t"))
        assertEquals(null, tagOf(event, "x"))
        assertEquals(null, tagOf(event, "size"))
    }

    @Test
    fun `authorization header uses scheme plus standard base64 with padding`() = runBlocking {
        val event = BlossomAuth.sign(FakeSigner(), BlossomAuth.TYPE_UPLOAD, "Uploading image")
        val token = BlossomAuth.rawToken(event)

        // rawToken is the bare base64 (no scheme); headerValue adds it exactly once.
        assertFalse("raw token must not carry the scheme", token.startsWith("Nostr "))
        assertEquals("Nostr $token", BlossomAuth.headerValue(event))

        // Decodes with the STRICT standard decoder — this is what khatru-based
        // servers do. A url-safe/unpadded token throws here, and is exactly
        // what servers rejected with HTTP 400.
        val decoded = String(Base64.getDecoder().decode(token), Charsets.UTF_8)
        assertFalse("must not use the url-safe alphabet", token.contains('-') || token.contains('_'))

        val parsed = NostrJson.decodeFromString(Event.serializer(), decoded)
        assertEquals(24242, parsed.kind)
        assertEquals(event.id, parsed.id)
    }

    @Test
    fun `token json round-trips back to the signed event`() = runBlocking {
        val event = BlossomAuth.sign(FakeSigner(), BlossomAuth.TYPE_DELETE, "Deleting blob", hash = "cd".repeat(32))
        val decoded = String(Base64.getDecoder().decode(BlossomAuth.rawToken(event)), Charsets.UTF_8)
        val parsed = NostrJson.decodeFromString(Event.serializer(), decoded)
        assertEquals(event, parsed)
        assertNotNull(parsed.sig)
        // The id must still be the NIP-01 hash of the canonical serialization,
        // i.e. the token was not mutated on the way through base64.
        assertEquals(event.computeIdForTest(), parsed.id)
    }

    /** NIP-01 id of a signed event, recomputed from its fields. */
    private fun Event.computeIdForTest(): String =
        social.tbone.nostr.Event.sha256(
            social.tbone.nostr.Event.serializeForId(pubkey, createdAt, kind, tags, content),
        )

    @Test
    fun `builds tags as json arrays of string primitives`() = runBlocking {
        val event = BlossomAuth.sign(FakeSigner(), BlossomAuth.TYPE_UPLOAD, "Uploading image", hash = "ef".repeat(32))
        assertTrue("every tag needs at least a name and a value", event.tags.all { it.size >= 2 })
        assertTrue(event.tags.all { tag -> tag.all { it is JsonPrimitive } })
        assertTrue(
            "tag names must serialise as plain strings",
            event.tags.all { (it.first() as JsonPrimitive).isString },
        )
    }
}
