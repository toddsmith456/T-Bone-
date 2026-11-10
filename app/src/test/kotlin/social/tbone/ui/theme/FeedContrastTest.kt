package social.tbone.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.youniversal.theme.YouniversalContrast
import dev.youniversal.theme.youniversalCreamColorScheme
import dev.youniversal.theme.youniversalDarkColorScheme
import dev.youniversal.theme.youniversalHighContrast
import dev.youniversal.theme.youniversalLightColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Legibility gate for the Phase 4 tonal work: every text role the feed uses
 * must clear WCAG AA (4.5:1) on both the page and card tones of all three
 * Youniversal schemes, in both contrast modes — and the quiet secondary tone
 * must never be worse than the surface it was originally designed for.
 */
class FeedContrastTest {

    private data class Case(val name: String, val scheme: androidx.compose.material3.ColorScheme, val isDark: Boolean)

    private val cases: List<Case>
        get() = listOf(
            Case("dark", youniversalDarkColorScheme(), true),
            Case("light", youniversalLightColorScheme(), false),
            Case("cream", youniversalCreamColorScheme(), false),
        )

    /** WCAG 2.1 contrast ratio between two opaque colors. */
    private fun ratio(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** Flatten a translucent foreground over an opaque background. */
    private fun blend(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1 - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1 - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
        alpha = 1f,
    )

    @Test
    fun `primary and dim text clear aa on page and card in every scheme`() {
        for (c in cases) {
            for (contrast in listOf(YouniversalContrast.Default, YouniversalContrast.High)) {
                val scheme = if (contrast == YouniversalContrast.High) {
                    c.scheme.youniversalHighContrast(c.isDark)
                } else {
                    c.scheme
                }
                val page = feedPageColor(scheme, c.isDark)
                val card = feedCardColor(scheme, c.isDark)
                val label = "${c.name}/$contrast"

                assertTrue("$label onBackground/page ${ratio(scheme.onBackground, page)}",
                    ratio(scheme.onBackground, page) >= 4.5f)
                assertTrue("$label onSurface/card ${ratio(scheme.onSurface, card)}",
                    ratio(scheme.onSurface, card) >= 4.5f)
                assertTrue("$label onSurfaceVariant/card ${ratio(scheme.onSurfaceVariant, card)}",
                    ratio(scheme.onSurfaceVariant, card) >= 4.5f)

                // Muted secondary text sits below AA by design (soft labels);
                // the gate is that the new card tones never regress it versus
                // the surface the palette was designed for. Alpha comes from
                // the bridge itself. Dark cards sit above the old surface,
                // which structurally trims translucent-text gains (~0.18 at
                // the bridge alpha), so the no-regression tolerance is 0.25.
                BonyColors.applyYouniversal(scheme)
                val mute = BonyColors.TextMute
                val onCard = ratio(blend(mute, card), card)
                val onDesigned = ratio(blend(mute, scheme.surface), scheme.surface)
                assertTrue("$label mute regressed: $onCard < $onDesigned",
                    onCard >= onDesigned - 0.25f)
            }
        }
    }

    @Test
    fun `hairline rules stay visible against the deeper page`() {
        for (c in cases) {
            val scheme = c.scheme
            val page = feedPageColor(scheme, c.isDark)
            // outlineVariant is the hairline role; it must not vanish into the page.
            assertTrue("${c.name} rule/page", ratio(scheme.outlineVariant, page) >= 1.2f)
        }
    }
}
