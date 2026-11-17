package social.tbone.account.signer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import social.tbone.nostr.UnsignedEvent

class ClientEventTagTest {
    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun values(tag: JsonArray): List<String> =
        tag.map { (it as JsonPrimitive).content }

    private fun event(tags: List<JsonArray> = emptyList()) = UnsignedEvent(
        pubkey = "a".repeat(64),
        createdAt = 1L,
        kind = 1,
        tags = tags,
        content = "hello",
    )

    @Test
    fun `adds T-Bone client tag to enabled templates before signing`() {
        val source = event(listOf(tag("t", "android")))

        val decorated = ClientEventTag.decorate(source, enabled = true)

        assertEquals(listOf(tag("t", "android"), tag("client", "T-Bone")), decorated.tags)
        assertEquals("T-Bone", values(decorated.tags.last())[1])
    }

    @Test
    fun `disabled client tagging leaves the unsigned event untouched`() {
        val source = event(listOf(tag("client", "Existing Client")))

        assertSame(source, ClientEventTag.decorate(source, enabled = false))
    }

    @Test
    fun `does not add a duplicate when a client tag is already present`() {
        val source = event(listOf(tag("client", "Another Client"), tag("t", "android")))

        assertSame(source, ClientEventTag.decorate(source, enabled = true))
    }

    @Test
    fun `leaves NIP-13 PoW mined events untouched so event id stays valid`() {
        val source = event(listOf(tag("t", "steak"), tag("nonce", "48219", "16")))

        assertSame(source, ClientEventTag.decorate(source, enabled = true))
    }
}
