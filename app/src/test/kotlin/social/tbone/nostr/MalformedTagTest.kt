package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MalformedTagTest {
    @Test fun `non primitive tag members do not crash parsing`() {
        val tag = Tag.fromJsonArray(JsonArray(listOf(JsonPrimitive("p"), JsonObject(emptyMap()), JsonArray(emptyList()), JsonNull)))
        assertEquals("p", tag.name)
        assertEquals(listOf("p", "", "", ""), tag.values)
    }
}
