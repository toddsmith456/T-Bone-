package dev.youniversal.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity checks for the one-tap look bundles and their palette resolution. */
class YouniversalLookTest {

    @Test
    fun `five bundle looks with valid ranges`() {
        val bundles = YouniversalLook.entries.filter { it.isBundle }
        assertEquals(5, bundles.size)
        bundles.forEach { look ->
            assertTrue("${look.name} label", look.label.isNotBlank())
            assertTrue("${look.name} description", look.description.isNotBlank())
            assertTrue("${look.name} corners", look.cornerScale in 0f..2f)
            assertTrue("${look.name} font scale", look.fontScale in 0.7f..2f)
        }
    }

    @Test
    fun `custom is an indicator not a bundle`() {
        assertFalse(YouniversalLook.CUSTOM.isBundle)
    }

    @Test
    fun `signal is the legibility look`() {
        assertEquals(YouniversalContrast.High, YouniversalLook.SIGNAL.contrast)
        assertTrue(YouniversalLook.SIGNAL.fontScale > 1f)
        assertEquals(Color.Unspecified, YouniversalLook.SIGNAL.accentSeed)
    }

    @Test
    fun `looks resolve to distinct palettes`() {
        val paper = youniversalLookScheme(YouniversalLook.PAPER, isSystemDark = false)
        val midnight = youniversalLookScheme(YouniversalLook.MIDNIGHT, isSystemDark = false)
        val porcelain = youniversalLookScheme(YouniversalLook.PORCELAIN, isSystemDark = false)
        assertNotEquals(paper.background, midnight.background)
        assertNotEquals(paper.background, porcelain.background)
        assertNotEquals(porcelain.primary, youniversalLookScheme(YouniversalLook.ROSEWOOD, false).primary)
    }

    @Test
    fun `high contrast keeps the background and hardens the foregrounds`() {
        val base = youniversalScheme(
            backgroundStyle = YouniversalBackgroundStyle.Light,
            contrast = YouniversalContrast.Default,
            accentSeed = Color.Unspecified,
            isSystemDark = false,
        )
        val high = youniversalScheme(
            backgroundStyle = YouniversalBackgroundStyle.Light,
            contrast = YouniversalContrast.High,
            accentSeed = Color.Unspecified,
            isSystemDark = false,
        )
        assertEquals(base.background, high.background)
        assertEquals(Color.Black, high.onBackground)
        assertNotEquals(base.onBackground, high.onBackground)
    }

    @Test
    fun `seeded look keeps brand error red`() {
        val paper = youniversalLookScheme(YouniversalLook.PAPER, isSystemDark = false)
        val plain = youniversalColorScheme(YouniversalBackgroundStyle.Cream, isSystemInDarkTheme = false)
        assertEquals(plain.error, paper.error)
    }
}
