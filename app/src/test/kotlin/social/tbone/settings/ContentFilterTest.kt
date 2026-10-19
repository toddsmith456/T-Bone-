package social.tbone.settings

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

class ContentFilterTest {
    private fun tag(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private fun event(
        id: String,
        pubkey: String,
        tags: List<JsonArray> = emptyList(),
        content: String = "",
        kind: Int = EventKind.TEXT_NOTE,
    ) = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 1_800_000_000L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    @Test
    fun `reply to blocked author is hidden even when replier is not blocked`() {
        val blockedAuthor = "b".repeat(64)
        val reply = event(
            id = "reply",
            pubkey = "r".repeat(64),
            tags = listOf(
                tag("e", "parent", "", "reply"),
                tag("p", blockedAuthor),
            ),
        )

        assertTrue(ContentFilter.shouldHide(reply, setOf(blockedAuthor), false, emptySet()))
    }

    @Test
    fun `mention of blocked author in a non-reply remains visible`() {
        val blockedAuthor = "b".repeat(64)
        val note = event(
            id = "note",
            pubkey = "n".repeat(64),
            tags = listOf(tag("p", blockedAuthor)),
            content = "I mentioned someone, this is not a reply",
        )

        assertFalse(ContentFilter.shouldHide(note, setOf(blockedAuthor), false, emptySet()))
    }

    @Test
    fun `reply is hidden when the referenced parent author is blocked`() {
        val blockedAuthor = "b".repeat(64)
        val parent = event(id = "parent", pubkey = blockedAuthor, content = "parent")
        val reply = event(
            id = "reply",
            pubkey = "r".repeat(64),
            tags = listOf(tag("e", parent.id, "", "reply")),
        )

        assertTrue(
            ContentFilter.shouldHide(
                reply,
                blockedPubkeys = setOf(blockedAuthor),
                hideNsfw = false,
                hideWords = emptySet(),
                referencedEvents = mapOf(parent.id to parent),
            ),
        )
    }

    @Test
    fun `hidden words match case-insensitively`() {
        val note = event(id = "note", pubkey = "n".repeat(64), content = "Please HideThis phrase")

        assertTrue(ContentFilter.shouldHide(note, emptySet(), false, setOf("hidethis")))
        assertFalse(ContentFilter.shouldHide(note, emptySet(), false, setOf("absent")))
    }

    @Test
    fun `reposts of blocked authors are hidden without the target cached`() {
        val blockedAuthor = "b".repeat(64)
        val repost = event(
            id = "repost",
            pubkey = "r".repeat(64),
            tags = listOf(tag("e", "target", "", "mention"), tag("p", blockedAuthor)),
            kind = EventKind.REPOST,
        )

        assertTrue(ContentFilter.shouldHide(repost, setOf(blockedAuthor), false, emptySet()))
    }
}
