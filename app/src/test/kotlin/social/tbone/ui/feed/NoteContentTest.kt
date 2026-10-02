package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Nip19

/**
 * Body parsing: an image URL must become a media item (so quoted notes can show
 * their picture), and a quote reference must be extractable and strippable.
 */
class NoteContentTest {

    @Test fun imageUrlBecomesMediaItem() {
        val parsed = parseNoteContent("look at this\nhttps://example.com/cat.jpg")
        assertEquals(1, parsed.mediaItems.size)
        assertTrue(parsed.mediaItems.first() is MediaItem.Image)
        assertEquals("https://example.com/cat.jpg", (parsed.mediaItems.first() as MediaItem.Image).url)
        assertTrue(parsed.text.contains("look at this"))
    }

    @Test fun multipleImagesAllBecomeMediaItems() {
        val parsed = parseNoteContent(
            "two pics https://a.example/1.png and https://b.example/2.webp",
        )
        assertEquals(2, parsed.mediaItems.size)
    }

    @Test fun gifAndQueryStringsAreImages() {
        val parsed = parseNoteContent("https://cdn.example/fun.gif?size=large")
        assertEquals(1, parsed.mediaItems.size)
    }

    @Test fun videoIsMediaButNotAnImage() {
        val parsed = parseNoteContent("https://cdn.example/clip.mp4")
        assertEquals(1, parsed.mediaItems.size)
        assertTrue(parsed.mediaItems.first() is MediaItem.Video)
    }

    @Test fun plainTextHasNoMedia() {
        val parsed = parseNoteContent("just words and a link https://example.com/page")
        assertTrue(parsed.mediaItems.isEmpty())
        assertTrue(parsed.text.contains("https://example.com/page"))
    }

    @Test fun inlineQuoteReferenceIsExtracted() {
        val id = "ab".repeat(32)
        val note = Nip19.hexToNote(id)
        assertEquals(id, extractInlineQuoteId("replying to nostr:$note here"))
        assertNull(extractInlineQuoteId("no reference at all"))
    }

    @Test fun quoteReferenceIsStrippedFromTheBody() {
        val id = "cd".repeat(32)
        val note = Nip19.hexToNote(id)
        val body = stripQuoteRefs("my thoughts on this\n\nnostr:$note", id)
        assertEquals("my thoughts on this", body)
        assertTrue(!body.contains("nostr:"))
    }

    @Test fun unrelatedInlineReferenceSurvivesStripping() {
        val quoted = "ef".repeat(32)
        val other = "12".repeat(32)
        // A reference in the middle of the text names another note, so it stays
        // (only the trailing quote form and the quoted id itself are removed).
        val body = stripQuoteRefs("see also nostr:${Nip19.hexToNote(other)} for details", quoted)
        assertTrue(body.contains("nostr:"))
    }
}
