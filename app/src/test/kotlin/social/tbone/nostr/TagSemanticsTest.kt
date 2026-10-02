package social.tbone.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-10 tag handling. The reply/root helpers decide which note a thread hangs
 * off, so a mistake here shows up as "the parent note cannot be loaded".
 */
class TagSemanticsTest {

    private fun tags(vararg values: String) = Tag(values.toList())

    @Test fun markedReplyAndRootArePreferred() {
        val t = listOf(
            tags("e", "root-id", "", "root"),
            tags("e", "reply-id", "", "reply"),
            tags("p", "someone"),
        )
        assertEquals("root-id", t.rootEventId)
        assertEquals("reply-id", t.replyEventId)
    }

    @Test fun legacyPositionalTagsStillResolve() {
        val t = listOf(tags("e", "root-id"), tags("e", "mid-id"), tags("e", "parent-id"))
        assertEquals("root-id", t.rootEventId)
        assertEquals("parent-id", t.replyEventId)
    }

    @Test fun singleETagIsBothRootAndParent() {
        val t = listOf(tags("e", "only-id"))
        assertEquals("only-id", t.rootEventId)
        assertEquals("only-id", t.replyEventId)
        assertTrue(t.isReply)
    }

    @Test fun mentionTagsDoNotMasqueradeAsTheParent() {
        val t = listOf(
            tags("e", "root-id", "", "root"),
            tags("e", "reply-id", "", "reply"),
            tags("e", "mentioned-id", "", "mention"),
        )
        assertEquals("reply-id", t.replyEventId)
        assertEquals("root-id", t.rootEventId)
    }

    @Test fun mentionOnlyNoteIsNotAReply() {
        val t = listOf(tags("e", "mentioned-id", "", "mention"))
        assertFalse(t.isReply)
        assertNull(t.rootEventId)
    }

    @Test fun quoteTagIsNotAReply() {
        val t = listOf(tags("q", "quoted-id"), tags("p", "author"))
        assertFalse(t.isReply)
        assertEquals("quoted-id", t.quotedEventId)
        assertNull(t.replyEventId)
    }

    @Test fun noTagsMeansNoParent() {
        val t = listOf(tags("p", "someone"))
        assertFalse(t.isReply)
        assertNull(t.rootEventId)
        assertNull(t.replyEventId)
    }
}
