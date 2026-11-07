package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.unit.dp
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

    @Test fun commonAudioFormatsBecomePlayerItemsAndLeaveNoVisibleUrlText() {
        val parsed = parseNoteContent("listen: https://cdn.example/voice.m4a https://cdn.example/song.flac")
        assertEquals(2, parsed.mediaItems.size)
        assertTrue(parsed.mediaItems.all { it is MediaItem.Audio })
        assertTrue(parsed.text.contains("listen:"))
        assertTrue(!parsed.text.contains("https://cdn.example"))
    }

    @Test fun broaderVideoContainersAreRecognized() {
        val parsed = parseNoteContent("https://cdn.example/clip.avi")
        assertEquals(1, parsed.mediaItems.size)
        assertTrue(parsed.mediaItems.first() is MediaItem.Video)
    }

    @Test fun plainTextHasNoMedia() {
        val parsed = parseNoteContent("just words and a link https://example.com/page")
        assertTrue(parsed.mediaItems.isEmpty())
        assertTrue(parsed.text.contains("https://example.com/page"))
    }

    @Test fun mediaUrlsUseSharedProseBoundariesAndPreservePunctuation() {
        val parsed = parseNoteContent("Photo (https://cdn.example/a(b).jpg), gif https://cdn.example/a.gif?x=1! video https://cdn.example/v.mp4. audio https://cdn.example/v.m4a;")
        assertEquals(listOf("https://cdn.example/a(b).jpg", "https://cdn.example/a.gif?x=1", "https://cdn.example/v.mp4", "https://cdn.example/v.m4a"), parsed.mediaItems.map { it.url })
        assertEquals("Photo (), gif ! video . audio ;", parsed.text)
    }

    @Test fun mediaExtensionComesFromPathNotHostQueryOrFragment() {
        assertTrue(parseNoteContent("https://host.jpg/page?next=picture.png").mediaItems.isEmpty())
        assertTrue(parseNoteContent("https://host.test/page#picture.png").mediaItems.isEmpty())
        assertEquals("https://host.test/photo.JPG?next=thing.mp4#fragment", parseNoteContent("https://host.test/photo.JPG?next=thing.mp4#fragment").mediaItems.single().url)
        assertEquals("https://host.test/photo%29.png", parseNoteContent("<https://host.test/photo%29.png>").mediaItems.single().url)
    }

    @Test fun inlineQuoteReferenceIsExtracted() {
        val id = "ab".repeat(32)
        val note = Nip19.hexToNote(id)
        assertEquals(id, extractInlineQuoteId("replying to nostr:$note here"))
        assertNull(extractInlineQuoteId("no reference at all"))
    }

    @Test fun referencesAfterProfilesAndMultipleQuotesAreFound() {
        val first = "ab".repeat(32)
        val second = "cd".repeat(32)
        val profile = Nip19.hexToNpub("ef".repeat(32))
        val text = "nostr:$profile nostr:${Nip19.hexToNote(first)} nostr:${Nip19.hexToNote(second)}"
        assertEquals(listOf(first, second), extractInlineQuoteIds(text))
        assertEquals(first, extractInlineQuoteId(text))
    }

    @Test fun strippingOneQuotePreservesOtherTrailingReferences() {
        val first = "ab".repeat(32)
        val second = "cd".repeat(32)
        val text = "hello nostr:${Nip19.hexToNote(first)} nostr:${Nip19.hexToNote(second)}"
        val result = stripQuoteRefs(text, first)
        assertTrue(!result.contains(Nip19.hexToNote(first)))
        assertTrue(result.contains(Nip19.hexToNote(second)))
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

    @Test fun mediaFramePreservesFitSizeAndTrimsOnlyLetterboxSpace() {
        val bounds = fitMediaFrame(400.dp, 260.dp, aspectRatio = 2f)!!
        assertEquals(400f, bounds.width.value, 0.01f)
        assertEquals(200f, bounds.height.value, 0.01f)

        val portrait = fitMediaFrame(400.dp, 260.dp, aspectRatio = 0.5f)!!
        assertEquals(130f, portrait.width.value, 0.01f)
        assertEquals(260f, portrait.height.value, 0.01f)

        assertNull(fitMediaFrame(400.dp, 260.dp, aspectRatio = Float.NaN))
    }

    @Test fun videoAspectRatioAccountsForRotationAndPixelAspect() {
        assertEquals(16f / 9f, videoDisplayAspectRatio(1920, 1080, 1f, 0)!!, 0.001f)
        assertEquals(9f / 16f, videoDisplayAspectRatio(1920, 1080, 1f, 90)!!, 0.001f)
        assertEquals(1f / (32f / 9f), videoDisplayAspectRatio(1920, 1080, 2f, 270)!!, 0.001f)
        assertNull(videoDisplayAspectRatio(0, 1080, 1f, 0))
    }
}
