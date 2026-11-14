package social.tbone.ui.compose

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
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
}
