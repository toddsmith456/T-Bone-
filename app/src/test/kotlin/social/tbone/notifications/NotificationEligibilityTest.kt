package social.tbone.notifications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

/**
 * The notification eligibility gate: relays are untrusted, so an event may
 * only become a notification when the event itself proves it involves the
 * account owner. These are the regression tests for notification tabs
 * filling with strangers' posts labelled as mentions (relays that ignore
 * the #p filter, especially on the "load older" path).
 */
class NotificationEligibilityTest {
    private val me = "11".repeat(32)
    private val other = "22".repeat(32)
    private val third = "33".repeat(32)
    private val myNote = "e1".repeat(32)
    private val myPoll = "e2".repeat(32)
    private val otherNote = "e3".repeat(32)
    private val ownIds = setOf(myNote, myPoll)
    private val isOwn: (String) -> Boolean = { it in ownIds }

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun event(
        kind: Int,
        pubkey: String = other,
        tags: List<JsonArray> = emptyList(),
        content: String = "hello",
    ) = Event(
        id = "f".repeat(64),
        pubkey = pubkey,
        createdAt = 123L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "c".repeat(128),
    )

    private fun classify(e: Event) = classifyNotification(e, me, isOwn)

    @Test
    fun `a post that tags nobody is not a notification`() {
        assertNull(classify(event(EventKind.TEXT_NOTE)))
    }

    @Test
    fun `a post tagging only someone else is not a notification`() {
        val e = event(EventKind.TEXT_NOTE, tags = listOf(tag("p", third)))
        assertNull(classify(e))
    }

    @Test
    fun `a post tagging me is a mention`() {
        val e = event(EventKind.TEXT_NOTE, tags = listOf(tag("p", me)))
        assertEquals(NotificationClass.MENTION, classify(e))
    }

    @Test
    fun `a reply to someone else's note that never tags me is not a notification`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(tag("e", otherNote, "", "reply"), tag("p", third)),
        )
        assertNull(classify(e))
    }

    @Test
    fun `a reply deep in my thread, to someone else, tagging nobody, is not a notification`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(
                tag("e", myNote, "", "root"),
                tag("e", otherNote, "", "reply"),
                tag("p", third),
            ),
        )
        assertNull(classify(e))
    }

    @Test
    fun `a reply to my note is a reply even without any p tag`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(tag("e", myNote, "", "reply")),
        )
        assertEquals(NotificationClass.REPLY, classify(e))
    }

    @Test
    fun `a reply in someone else's thread that tags me is a reply`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(tag("e", otherNote, "", "reply"), tag("p", me)),
        )
        assertEquals(NotificationClass.REPLY, classify(e))
    }

    @Test
    fun `a quote of my note is a quote`() {
        val e = event(EventKind.TEXT_NOTE, tags = listOf(tag("q", myNote)))
        assertEquals(NotificationClass.QUOTE, classify(e))
    }

    @Test
    fun `quoting someone else while tagging me is a mention, not a quote`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(tag("q", otherNote), tag("p", me)),
        )
        assertEquals(NotificationClass.MENTION, classify(e))
    }

    @Test
    fun `an explicit e-tag mention of my note is a mention`() {
        val e = event(
            EventKind.TEXT_NOTE,
            tags = listOf(tag("e", myNote, "", "mention")),
        )
        assertEquals(NotificationClass.MENTION, classify(e))
    }

    @Test
    fun `reactions only count on my notes`() {
        val mine = event(EventKind.REACTION, tags = listOf(tag("e", myNote), tag("p", me)), content = "+")
        assertEquals(NotificationClass.REACTION, classify(mine))
        val theirs = event(EventKind.REACTION, tags = listOf(tag("e", otherNote), tag("p", me)), content = "+")
        assertNull(classify(theirs))
    }

    @Test
    fun `a repost of my note counts without a p tag`() {
        val e = event(EventKind.REPOST, tags = listOf(tag("e", myNote)))
        assertEquals(NotificationClass.REPOST, classify(e))
        val theirs = event(EventKind.REPOST, tags = listOf(tag("e", otherNote)))
        assertNull(classify(theirs))
    }

    @Test
    fun `poll votes only count on my polls`() {
        val mine = event(EventKind.POLL_RESPONSE, tags = listOf(tag("e", myPoll)))
        assertEquals(NotificationClass.VOTE, classify(mine))
        val theirs = event(EventKind.POLL_RESPONSE, tags = listOf(tag("e", otherNote)))
        assertNull(classify(theirs))
    }

    @Test
    fun `my own events never notify`() {
        val e = event(EventKind.TEXT_NOTE, pubkey = me, tags = listOf(tag("p", me)))
        assertNull(classify(e))
    }

    @Test
    fun `follow lists and other kinds never notify`() {
        val e = event(EventKind.FOLLOW_LIST, tags = listOf(tag("p", me)))
        assertNull(classify(e))
    }
}
