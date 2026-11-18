package social.tbone.ui.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Test
import social.tbone.nostr.Event

class ComposeReplyTagsTest {
    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun event(id: String, tags: List<JsonArray> = emptyList()) = Event(
        id = id,
        pubkey = "author",
        createdAt = 1L,
        kind = 1,
        tags = tags,
        content = "parent",
        sig = "",
    )

    @Test
    fun directReplyHasExplicitParentAndAuthor() {
        assertEquals(
            listOf(tag("e", "parent", "", "reply"), tag("p", "author")),
            nip10ReplyTags(event("parent")),
        )
    }

    @Test
    fun nestedReplyRetainsRootAndParentAndParticipants() {
        val parent = event(
            "parent",
            listOf(
                tag("e", "root", "", "root"),
                tag("e", "parent", "", "reply"),
                tag("p", "author"),
                tag("p", "earlier-participant"),
            ),
        )
        assertEquals(
            listOf(
                tag("e", "root", "", "root"),
                tag("e", "parent", "", "reply"),
                tag("p", "author"),
                tag("p", "earlier-participant"),
            ),
            nip10ReplyTags(parent),
        )
    }
}
