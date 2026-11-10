package social.tbone.ui.theme

import androidx.compose.ui.graphics.luminance
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.youniversalCreamColorScheme
import dev.youniversal.theme.youniversalDarkColorScheme
import dev.youniversal.theme.youniversalHighContrast
import dev.youniversal.theme.youniversalLightColorScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feed cards must lift off the page: under every Youniversal background the
 * card tone has to be strictly brighter than the page tone. Classic-mode
 * divider expectations are pinned as well.
 */
class FeedSurfaceTest {

    @Test
    fun `cards lift off the page in every youniversal scheme`() {
        val cases = listOf(
            youniversalDarkColorScheme() to true,
            youniversalLightColorScheme() to false,
            youniversalCreamColorScheme() to false,
        )
        for ((scheme, isDark) in cases) {
            val page = feedPageColor(scheme, isDark)
            val card = feedCardColor(scheme, isDark)
            assertTrue("page and card must differ (isDark=$isDark)", page != card)
            assertTrue(
                "card must be brighter than page (isDark=$isDark: ${card.luminance()} vs ${page.luminance()})",
                card.luminance() > page.luminance(),
            )
        }
    }

    @Test
    fun `high-contrast schemes keep the same lift direction`() {
        val highDark = youniversalDarkColorScheme().youniversalHighContrast(true)
        assertTrue(
            "dark high contrast",
            feedCardColor(highDark, isDark = true).luminance() > feedPageColor(highDark, isDark = true).luminance(),
        )
        val highLight = youniversalLightColorScheme().youniversalHighContrast(false)
        assertTrue(
            "light high contrast",
            feedCardColor(highLight, isDark = false).luminance() >
                feedPageColor(highLight, isDark = false).luminance(),
        )
    }

    @Test
    fun `bridge lifts surfaces above the page in every scheme`() {
        for ((scheme, isDark) in listOf(
            youniversalDarkColorScheme() to true,
            youniversalLightColorScheme() to false,
            youniversalCreamColorScheme() to false,
        )) {
            BonyColors.applyYouniversal(scheme)
            assertTrue("page is the deeper tone", BonyColors.Bg == feedPageColor(scheme, isDark))
            assertTrue("surface is the card tone", BonyColors.Surface == feedCardColor(scheme, isDark))
            assertTrue(
                "surface lifts off page (${BonyColors.Surface.luminance()} vs ${BonyColors.Bg.luminance()})",
                BonyColors.Surface.luminance() > BonyColors.Bg.luminance(),
            )
        }
    }

    @Test
    fun `post dividers classic always youniversal only at high contrast`() {
        // Classic: dividers are the historical separator, always shown.
        assertTrue(showPostDividers(youniversalEnabled = false, contrast = YouniversalContrast.Default))
        assertTrue(showPostDividers(youniversalEnabled = false, contrast = YouniversalContrast.High))
        // Youniversal cards float with gaps — hairlines would double up.
        assertFalse(showPostDividers(youniversalEnabled = true, contrast = YouniversalContrast.Default))
        // High contrast keeps them: separation matters more than polish there.
        assertTrue(showPostDividers(youniversalEnabled = true, contrast = YouniversalContrast.High))
    }

    @Test
    fun `schemes with identical background and surface are exactly the problem`() {
        // Pins the root cause this feature exists for: Youniversal's base
        // palette paints page and card the same colour, so the tonal pair is
        // what creates lift, not the base roles.
        assertTrue(youniversalDarkColorScheme().background == youniversalDarkColorScheme().surface)
        assertTrue(youniversalLightColorScheme().background == youniversalLightColorScheme().surface)
    }
}
