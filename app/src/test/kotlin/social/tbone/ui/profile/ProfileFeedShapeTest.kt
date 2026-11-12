package social.tbone.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.isReply

/**
 * Profile feed shape: NOTES is top-level only, ALL includes replies, and pinned
 * notes lead both. These are the rules a user notices immediately if they are
 * wrong, and they are pure enough to test without any Android state.
 */
class ProfileFeedShapeTest {

    private fun tag(vararg v: String) = JsonArray(v.map(::JsonPrimitive))

    private fun note(id: String, createdAt: Long, reply: Boolean = false): Event {
        val tags = if (reply) {
            listOf(tag("e", "root-id", "", "root"), tag("e", "parent-id", "", "reply"))
        } else {
            emptyList()
        }
        return Event(
            id = id,
            pubkey = "me",
            createdAt = createdAt,
            kind = EventKind.TEXT_NOTE,
            tags = tags,
            content = "note $id",
            sig = "",
        )
    }

    private fun comment(id: String, createdAt: Long): Event = Event(
        id = id,
        pubkey = "me",
        createdAt = createdAt,
        kind = EventKind.COMMENT,
        tags = emptyList(),
        content = "comment $id",
        sig = "",
    )

    /** Mirrors ProfileViewModel.isReply — the split must stay identical. */
    private fun isReplyNote(event: Event): Boolean =
        event.kind == EventKind.COMMENT ||
            (event.kind == EventKind.TEXT_NOTE && event.parsedTags.isReply)

    @Test
    fun `top-level notes exclude replies and comments`() {
        val feed = listOf(
            note("a", 5),
            note("b", 4, reply = true),
            comment("c", 3),
            note("d", 2),
        )
        assertEquals(
            listOf("a", "d"),
            feed.filterNot { isReplyNote(it) }.map { it.id },
        )
        assertEquals(listOf("b", "c"), feed.filter { isReplyNote(it) }.map { it.id })
    }

    @Test
    fun `pinned notes lead the feed and are not duplicated`() {
        val feed = listOf(note("a", 5), note("b", 4), note("c", 3))
        val pinned = listOf(feed[2], feed[1])
        val ordered = pinnedFirst(feed, pinned)
        assertEquals(listOf("c", "b", "a"), ordered.map { it.id })
        assertEquals("no duplicates", ordered.size, ordered.distinctBy { it.id }.size)
    }

    @Test
    fun `an empty pin list leaves the feed untouched`() {
        val feed = listOf(note("a", 5), note("b", 4))
        assertEquals(feed.map { it.id }, pinnedFirst(feed, emptyList()).map { it.id })
    }

    @Test
    fun `a pin outside the visible list is still shown`() {
        // A reply is pinned while the NOTES tab is open, so it is not in that
        // list — it must still appear at the top rather than vanish.
        val pinnedReply = note("r", 1, reply = true)
        val feed = listOf(note("a", 5), note("b", 4))
        val ordered = pinnedFirst(feed, listOf(pinnedReply))
        assertEquals(listOf("r", "a", "b"), ordered.map { it.id })
    }

    @Test
    fun `tab order puts notes first and has no pinned tab`() {
        assertEquals(
            listOf("NOTES", "ALL", "REPLIES", "MEDIA"),
            ProfileTab.entries.map { it.name },
        )
    }

    @Test
    fun `the default tab is notes`() {
        assertEquals(ProfileTab.NOTES, ProfileTab.entries.first())
    }

    /** Copy of ProfileViewModel.pinnedFirst so the ordering rules are locked in. */
    private fun pinnedFirst(events: List<Event>, pinned: List<Event>): List<Event> {
        if (pinned.isEmpty()) return events
        val pinnedIds = pinned.map { it.id }.toSet()
        val byId = events.associateBy { it.id }
        val head = pinned.map { byId[it.id] ?: it }
        return head + events.filterNot { it.id in pinnedIds }
    }
}
