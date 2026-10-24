package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Nip19

/**
 * What a share card prints as note text.
 *
 * Two shipped bugs are pinned here:
 *  - an image-only note printed "(empty note)" in the text slot, and
 *  - a quoted note printed the raw `nostr:nevent1…` string instead of a quote.
 */
class ShareCardBodyTest {

    private val eventId = "ab".repeat(32)
    private val authorId = "cd".repeat(32)

    private fun note(content: String) = social.tbone.nostr.Event(
        id = "event-id",
        pubkey = authorId,
        createdAt = 1L,
        kind = 1,
        tags = emptyList(),
        content = content,
        sig = "",
    )

    @Test
    fun anImageOnlyNoteHasAnEmptyTextSlot() {
        val event = note("https://blossom.example/blob/beef.jpeg")
        val media = ShareCardMediaParser.parse(event)

        val body = shareCardBodyText(event.content, media, quotedId = null)

        assertEquals(1, media.size)
        assertEquals("", body)
        assertTrue("no placeholder may be invented", !body.contains("empty"))
    }

    @Test
    fun aVideoOnlyNoteHasAnEmptyTextSlotToo() {
        val event = note("https://cdn.example/clip.mp4")
        val body = shareCardBodyText(event.content, ShareCardMediaParser.parse(event), quotedId = null)
        assertEquals("", body)
    }

    @Test
    fun aQuoteReferenceIsRemovedFromTheBody() {
        val nevent = Nip19.hexToNevent(eventId, authorId, 1)
        val event = note("look at this\n\nnostr:$nevent")
        val quotedId = event.parsedTags.firstOrNull { it.name == "q" }?.value()
        val inlineId = extractInlineQuoteId(event.content)

        assertEquals(eventId, inlineId)
        assertNull("no q tag on this note", quotedId)

        val body = shareCardBodyText(event.content, ShareCardMediaParser.parse(event), inlineId)

        assertEquals("look at this", body)
        assertTrue(!body.contains("nevent1"))
    }

    @Test
    fun textAroundAQuoteSurvives() {
        val nevent = Nip19.hexToNevent(eventId, authorId, 1)
        val event = note("this is the take nostr:$nevent wild, right?")
        val body = shareCardBodyText(
            event.content,
            ShareCardMediaParser.parse(event),
            extractInlineQuoteId(event.content),
        )
        assertTrue(body.startsWith("this is the take"))
        assertTrue(body.contains("wild, right?"))
        assertTrue(!body.contains("nostr:"))
    }
}
