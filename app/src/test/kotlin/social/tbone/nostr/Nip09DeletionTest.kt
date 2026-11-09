package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Test

class Nip09DeletionTest {
    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun values(tag: JsonArray): List<String> =
        tag.map { (it as JsonPrimitive).content }

    private fun event(kind: Int, tags: List<JsonArray> = emptyList()) = Event(
        id = "b".repeat(64),
        pubkey = "a".repeat(64),
        createdAt = 123L,
        kind = kind,
        tags = tags,
        content = "my note",
        sig = "c".repeat(128),
    )

    @Test
    fun `deletion request targets the note with e and k tags`() {
        val target = event(EventKind.TEXT_NOTE)

        val request = Nip09Deletion.forNote(
            requesterPubkey = target.pubkey,
            target = target,
            createdAtSeconds = 456L,
        )

        assertEquals(EventKind.DELETE, request.kind)
        assertEquals(target.pubkey, request.pubkey)
        assertEquals(456L, request.createdAt)
        assertEquals("", request.content)
        assertEquals(listOf("e", target.id), values(request.tags[0]))
        assertEquals(listOf("k", "1"), values(request.tags[1]))
    }

    @Test
    fun `addressable events also get an a tag`() {
        val target = event(30_023, listOf(tag("d", "article-slug")))

        val request = Nip09Deletion.forNote(
            requesterPubkey = target.pubkey,
            target = target,
        )

        assertEquals(listOf("e", target.id), values(request.tags[0]))
        assertEquals(listOf("a", "30023:${target.pubkey}:article-slug"), values(request.tags[1]))
        assertEquals(listOf("k", "30023"), values(request.tags[2]))
    }
}
