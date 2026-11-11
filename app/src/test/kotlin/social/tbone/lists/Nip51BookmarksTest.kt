package social.tbone.lists

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

/**
 * The NIP-51 mapping is the contract between this app's folders and every other
 * client, so it is covered directly: a regression here silently hides a user's
 * bookmarks on another app.
 */
class Nip51BookmarksTest {

    private fun tag(vararg v: String) = JsonArray(v.map(::JsonPrimitive))

    private fun event(kind: Int, tags: List<JsonArray>) = Event(
        id = "0".repeat(64),
        pubkey = "1".repeat(64),
        createdAt = 1,
        kind = kind,
        tags = tags,
        content = "",
        sig = "0".repeat(128),
    )

    @Test fun plainListBecomesMiscellaneous() {
        val a = "a".repeat(64)
        val parsed = Nip51Bookmarks.folder(event(EventKind.BOOKMARKS, listOf(tag("e", a))))!!
        assertEquals(Nip51Bookmarks.RemoteFolder.MISCELLANEOUS, parsed.title)
        assertTrue(parsed.isMiscellaneous)
        assertEquals("", parsed.dTag)
        assertEquals(listOf(a), parsed.eventIds)
    }

    @Test fun setUsesTitleTagAndDForAddress() {
        val a = "a".repeat(64)
        val parsed = Nip51Bookmarks.folder(
            event(EventKind.BOOKMARK_SET, listOf(tag("d", "reading"), tag("title", "Reading List"), tag("e", a))),
        )!!
        assertEquals("reading", parsed.dTag)
        assertEquals("Reading List", parsed.title)
        assertEquals(listOf(a), parsed.eventIds)
        assertTrue(!parsed.isMiscellaneous)
    }

    @Test fun setWithoutTitleFallsBackToItsIdentifier() {
        val parsed = Nip51Bookmarks.folder(event(EventKind.BOOKMARK_SET, listOf(tag("d", "reading"))))!!
        assertEquals("reading", parsed.title)
    }

    @Test fun duplicateAndBlankIdsCollapse() {
        val a = "a".repeat(64)
        val parsed = Nip51Bookmarks.folder(
            event(EventKind.BOOKMARKS, listOf(tag("e", a), tag("e", a), tag("e", ""), tag("p", a))),
        )!!
        // `p` tags are not bookmarks; blanks and repeats are dropped.
        assertEquals(listOf(a), parsed.eventIds)
    }

    @Test fun wrongKindIsRejected() {
        assertNull(Nip51Bookmarks.folder(event(EventKind.PIN_LIST, listOf(tag("e", "a".repeat(64))))))
    }

    @Test fun addressesMatchTheNip() {
        val pk = "1".repeat(64)
        assertEquals("a:10003:$pk:", Nip51Bookmarks.mainAddress(pk))
        assertEquals("a:30003:$pk:reading", Nip51Bookmarks.setAddress(pk, "reading"))
    }

    @Test fun tagsForSetCarryDTitleAndRelayHint() {
        val id = "a".repeat(64)
        val tags = Nip51Bookmarks.tags(
            kind = EventKind.BOOKMARK_SET,
            title = "Reading",
            dTag = "reading",
            bookmarks = listOf(id to "wss://relay.example"),
        )
        assertEquals(listOf("d", "reading"), tags[0])
        assertEquals(listOf("title", "Reading"), tags[1])
        assertEquals(listOf("e", id, "wss://relay.example"), tags[2])
    }

    @Test fun plainListTagsHaveNoDRTagOrTitle() {
        val id = "a".repeat(64)
        val tags = Nip51Bookmarks.tags(
            kind = EventKind.BOOKMARKS,
            title = null,
            dTag = null,
            bookmarks = listOf(id to null),
        )
        assertEquals(listOf(listOf("e", id)), tags)
    }
}
