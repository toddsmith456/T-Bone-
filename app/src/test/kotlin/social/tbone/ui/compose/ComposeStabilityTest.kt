package social.tbone.ui.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for Option 1A Bug #1 crash fixes in Compose:
 *  - URL-cleaning paste cursor/IME clamping (`StringIndexOutOfBoundsException`)
 *  - Scaled BlurHash bitmap subsampling (`OutOfMemoryError` on high-res photos)
 */
class ComposeStabilityTest {

    @Test
    fun `cleanPastedTextFieldValue clamps selection and clears IME composition when tracking params shrink text`() {
        val previous = TextFieldValue(text = "Check ", selection = TextRange(6))
        val raw = "Check https://example.com/post?utm_source=twitter&utm_medium=social&fbclid=1234567890"
        val pasted = TextFieldValue(
            text = raw,
            selection = TextRange(raw.length),
            composition = TextRange(raw.length - 10, raw.length),
        )

        val cleaned = cleanPastedTextFieldValue(previous = previous, next = pasted)

        assertTrue("Expected tracking parameters stripped from URL", cleaned.text.length < raw.length)
        assertEquals(TextRange(cleaned.text.length), cleaned.selection)
        assertNull("IME composition must be cleared after rewriting text", cleaned.composition)
    }

    @Test
    fun `cleanPastedTextFieldValue preserves original value for normal typing or clean paste`() {
        val previous = TextFieldValue(text = "plain ", selection = TextRange(6))
        val typed = TextFieldValue(
            text = "plain a",
            selection = TextRange(7),
            composition = TextRange(6, 7),
        )
        assertEquals(typed, cleanPastedTextFieldValue(previous, typed))

        val cleanPaste = TextFieldValue(
            text = "plain https://example.com/post",
            selection = TextRange(30),
        )
        assertEquals(cleanPaste, cleanPastedTextFieldValue(previous, cleanPaste))
    }

    @Test
    fun `computeBlurHashSampleSize downsamples high-resolution photos to avoid OOM`() {
        // 12MP camera photo (4000x3000)
        val sample12Mp = computeBlurHashSampleSize(4000, 3000, targetMaxDim = 128)
        assertTrue("Expected >= 16 sample size for 4000x3000, got $sample12Mp", sample12Mp >= 16)
        assertTrue("Sample size must be a power of two", (sample12Mp and (sample12Mp - 1)) == 0)

        // 48MP photo (8000x6000)
        val sample48Mp = computeBlurHashSampleSize(8000, 6000, targetMaxDim = 128)
        assertTrue("Expected >= 32 sample size for 8000x6000, got $sample48Mp", sample48Mp >= 32)
        assertTrue("Sample size must be a power of two", (sample48Mp and (sample48Mp - 1)) == 0)

        // Small image stays unscaled
        assertEquals(1, computeBlurHashSampleSize(120, 90, targetMaxDim = 128))
        assertEquals(1, computeBlurHashSampleSize(0, 0, targetMaxDim = 128))
    }

    @Test
    fun `insertReferenceBelowProse keeps caret at top with no trailing space on empty composer`() {
        val url = "https://blossom.primal.net/abc123.jpg"
        val updated = insertReferenceBelowProse(TextFieldValue("", TextRange(0)), url)

        assertEquals("\n\n$url", updated.text)
        assertFalse("Blossom URL must not have a trailing space", updated.text.endsWith(" "))
        assertEquals(TextRange(0), updated.selection)
        assertNull(updated.composition)
    }

    @Test
    fun `insertReferenceBelowProse stacks multiple uploads and snaps caret back to top prose`() {
        val first = "https://blossom.primal.net/one.jpg"
        val second = "https://blossom.primal.net/two.mp4"
        // Simulate caret having drifted to the end of the first link before the second upload arrives.
        val afterFirst = TextFieldValue("\n\n$first", TextRange(first.length + 2))
        val afterSecond = insertReferenceBelowProse(afterFirst, second)

        assertEquals("\n\n$first\n$second", afterSecond.text)
        assertEquals(TextRange(0), afterSecond.selection)
    }

    @Test
    fun `insertReferenceBelowProse preserves caret inside prose and snaps past-prose caret to prose end`() {
        val url = "https://blossom.primal.net/photo.png"

        // Caret mid-prose stays mid-prose.
        val midProse = insertReferenceBelowProse(
            TextFieldValue("Check this out", TextRange(6)),
            url,
        )
        assertEquals("Check this out\n\n$url", midProse.text)
        assertEquals(TextRange(6), midProse.selection)

        // Caret after trailing blank lines snaps back to end of prose instead of landing on the URL.
        val trailingNewlines = insertReferenceBelowProse(
            TextFieldValue("Check this out\n\n", TextRange(16)),
            url,
        )
        assertEquals("Check this out\n\n$url", trailingNewlines.text)
        assertEquals(TextRange(14), trailingNewlines.selection)

        // Second upload when prose already exists keeps caret at prose end even if selection was at end.
        val secondUrl = "https://blossom.primal.net/second.png"
        val withFirst = TextFieldValue(
            "Check this out\n\n$url",
            TextRange("Check this out\n\n$url".length),
        )
        val withSecond = insertReferenceBelowProse(withFirst, secondUrl)
        assertEquals("Check this out\n\n$url\n$secondUrl", withSecond.text)
        assertEquals(TextRange(14), withSecond.selection)
    }

    @Test
    fun `initialComposeTextFieldValue puts caret at top for quotes and at end for drafts`() {
        val quote = initialComposeTextFieldValue("\n\nnostr:nevent1qqsxyz")
        assertEquals("\n\nnostr:nevent1qqsxyz", quote.text)
        assertEquals(TextRange(0), quote.selection)

        // Uploading Blossom media onto a quote note keeps caret at 0.
        val quoteWithMedia = insertReferenceBelowProse(quote, "https://blossom.primal.net/clip.jpg")
        assertEquals("\n\nnostr:nevent1qqsxyz\nhttps://blossom.primal.net/clip.jpg", quoteWithMedia.text)
        assertEquals(TextRange(0), quoteWithMedia.selection)

        val draft = initialComposeTextFieldValue("Draft note body")
        assertEquals("Draft note body", draft.text)
        assertEquals(TextRange("Draft note body".length), draft.selection)
    }

    @Test
    fun `applyComposerValueChange blocks transient post-upload jump to end but allows typing and post-window taps`() {
        val text = "\n\nhttps://blossom.primal.net/pic.jpg"
        val current = TextFieldValue(text, TextRange(0))
        val guard = ProseCaretGuard(
            expectedText = text,
            expectedSelection = TextRange(0),
            expiresAtElapsedMs = 1_600L,
        )

        // Spurious focus/IME jump to end of URL within window is blocked.
        val (blocked, guardAfterBlock) = applyComposerValueChange(
            previous = current,
            incoming = TextFieldValue(text, TextRange(text.length)),
            guard = guard,
            nowElapsedMs = 1_100L,
        )
        assertEquals(TextRange(0), blocked.selection)
        assertNotNull(guardAfterBlock)

        // Real typing at the top immediately passes through and clears the guard.
        val typedText = "H$text"
        val (typed, guardAfterType) = applyComposerValueChange(
            previous = current,
            incoming = TextFieldValue(typedText, TextRange(1)),
            guard = guard,
            nowElapsedMs = 1_150L,
        )
        assertEquals(typedText, typed.text)
        assertEquals(TextRange(1), typed.selection)
        assertNull(guardAfterType)

        // After the guard window expires, tapping the end of the URL is permitted.
        val (afterExpiry, guardAfterExpiry) = applyComposerValueChange(
            previous = current,
            incoming = TextFieldValue(text, TextRange(text.length)),
            guard = guard,
            nowElapsedMs = 1_700L,
        )
        assertEquals(TextRange(text.length), afterExpiry.selection)
        assertNull(guardAfterExpiry)
    }

    @Test
    fun `showEmptyProsePlaceholder shows hint for empty or reference-only notes`() {
        assertTrue(showEmptyProsePlaceholder(""))
        assertTrue(showEmptyProsePlaceholder("\n\nhttps://blossom.primal.net/pic.jpg"))
        assertTrue(showEmptyProsePlaceholder("\n\nnostr:nevent1abc\nhttps://blossom.primal.net/pic.jpg"))
        assertFalse(showEmptyProsePlaceholder("Hello\n\nhttps://blossom.primal.net/pic.jpg"))
        assertFalse(showEmptyProsePlaceholder("\n\nJust prose on line three"))
    }
}
