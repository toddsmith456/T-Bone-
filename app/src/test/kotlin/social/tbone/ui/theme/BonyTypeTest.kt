package social.tbone.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Youniversal reading-hierarchy ladder must never touch classic mode:
 * with the Youniversal font unset, every promoted role has to render exactly
 * like the historical style it replaced.
 */
class BonyTypeTest {

    @After
    fun tearDown() {
        BonyType.setYouniversalFontFamily(null)
    }

    @Test
    fun `classic roles stay byte-identical to the historical styles`() {
        BonyType.setYouniversalFontFamily(null)

        assertEquals(15f, BonyType.body.fontSize.value)
        assertEquals(22f, BonyType.body.lineHeight.value)

        // name renders exactly like bodyDim in classic mode
        assertEquals(BonyType.bodyDim.fontSize, BonyType.name.fontSize)
        assertEquals(BonyType.bodyDim.lineHeight, BonyType.name.lineHeight)
        assertEquals(BonyType.bodyDim.letterSpacing, BonyType.name.letterSpacing)
        assertEquals(FontWeight.Normal, BonyType.name.fontWeight)

        // count renders exactly like metaDim in classic mode
        assertEquals(BonyType.metaDim.fontSize, BonyType.count.fontSize)
        assertEquals(BonyType.metaDim.lineHeight, BonyType.count.lineHeight)
        assertEquals(BonyType.metaDim.letterSpacing, BonyType.count.letterSpacing)
        assertEquals(FontWeight.Normal, BonyType.count.fontWeight)
    }

    @Test
    fun `youniversal ladder promotes reading roles only`() {
        BonyType.setYouniversalFontFamily(FontFamily.SansSerif)

        // body: larger and airier
        assertEquals(16f, BonyType.body.fontSize.value)
        assertEquals(25f, BonyType.body.lineHeight.value)
        assertEquals(15f, BonyType.bodyDim.fontSize.value)
        assertEquals(23f, BonyType.bodyDim.lineHeight.value)

        // author name: larger, semi-bold
        assertEquals(FontWeight.SemiBold, BonyType.name.fontWeight)
        assertEquals(16f, BonyType.name.fontSize.value)

        // counts: scannable medium weight
        assertEquals(FontWeight.Medium, BonyType.count.fontWeight)
        assertEquals(13f, BonyType.count.fontSize.value)

        // the rest of the ladder keeps its classic sizes
        assertEquals(13f, BonyType.meta.fontSize.value)
        assertEquals(12f, BonyType.metaDim.fontSize.value)
        assertEquals(20f, BonyType.title.fontSize.value)
    }
}
