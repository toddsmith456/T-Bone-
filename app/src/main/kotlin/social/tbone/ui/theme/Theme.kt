package social.tbone.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import social.tbone.R
import social.tbone.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * The T-bone palette, as live Compose state.
 *
 * Every screen reads these colors during composition, so switching theme mode
 * or the accent color re-composes the whole app instantly. The values are
 * applied by [BonyTheme] (and at startup by BonyApp); nothing else writes them.
 *
 * Only the accent family (green by default) is user-customizable — everything
 * else comes from the chosen light / dark / cream palette.
 */
object BonyColors {
    var Bg by mutableStateOf(Color(0xFF0A0A0A))
        private set
    var Surface by mutableStateOf(Color(0xFF0F0F0E))
        private set
    var SurfaceAlt by mutableStateOf(Color(0xFF0D0D0C))
        private set
    var Rule by mutableStateOf(Color(0xFF2A2826))
        private set
    var RuleStrong by mutableStateOf(Color(0xFF3A3733))
        private set
    var Text by mutableStateOf(Color(0xFFE8E6DF))
        private set
    var TextDim by mutableStateOf(Color(0xFF9A968D))
        private set
    var TextMute by mutableStateOf(Color(0xFF6B6862))
        private set
    var Warn by mutableStateOf(Color(0xFFE89A4A))
        private set
    var WarnDim by mutableStateOf(Color(0xFF7A5028))
        private set
    var WarnBg by mutableStateOf(Color(0x0FE89A4A))
        private set
    var Danger by mutableStateOf(Color(0xFFD96A5A))
        private set
    var Link by mutableStateOf(Color(0xFF82C8FF))
        private set

    /** The accent color — green by default, user-customizable via Settings. */
    var Accent by mutableStateOf(Color(0xFF7FDE6B))
        private set

    /** Darker variant of the accent (borders, secondary accents). */
    val AccentDim: Color get() = lerpColor(Accent, Color.Black, 0.5f)

    /** Very translucent accent (highlight backgrounds). */
    val AccentBg: Color get() = Accent.copy(alpha = 10f / 255f)

    /** Default accent color (the original Bony green). */
    val DefaultAccent: Color get() = Color(0xFF7FDE6B)

    /** Applies the base palette for a theme mode (does not touch the accent). */
    fun applyMode(mode: ThemeMode) {
        when (mode) {
            ThemeMode.LIGHT -> {
                Bg = Color(0xFFF7F6F3)
                Surface = Color(0xFFFFFFFF)
                SurfaceAlt = Color(0xFFF0EFEB)
                Rule = Color(0xFFE2E0DA)
                RuleStrong = Color(0xFFC9C6BF)
                Text = Color(0xFF1C1B19)
                TextDim = Color(0xFF55534D)
                TextMute = Color(0xFF8A8780)
                Warn = Color(0xFFB26A1F)
                WarnDim = Color(0xFF8A5318)
                WarnBg = Color(0x14E89A4A)
                Danger = Color(0xFFC74B3D)
                Link = Color(0xFF1E6FB8)
            }
            ThemeMode.DARK -> {
                Bg = Color(0xFF0A0A0A)
                Surface = Color(0xFF0F0F0E)
                SurfaceAlt = Color(0xFF0D0D0C)
                Rule = Color(0xFF2A2826)
                RuleStrong = Color(0xFF3A3733)
                Text = Color(0xFFE8E6DF)
                TextDim = Color(0xFF9A968D)
                TextMute = Color(0xFF6B6862)
                Warn = Color(0xFFE89A4A)
                WarnDim = Color(0xFF7A5028)
                WarnBg = Color(0x0FE89A4A)
                Danger = Color(0xFFD96A5A)
                Link = Color(0xFF82C8FF)
            }
            ThemeMode.CREAM -> {
                // Dimmed warm cream — an old book page, muted several steps
                // down from the original so it's easy on the eyes.
                Bg = Color(0xFFD9CEB0)
                Surface = Color(0xFFE1D7BB)
                SurfaceAlt = Color(0xFFD0C3A1)
                Rule = Color(0xFFB8A987)
                RuleStrong = Color(0xFF9E8D68)
                Text = Color(0xFF262015)
                TextDim = Color(0xFF4B422D)
                TextMute = Color(0xFF6A5E44)
                Warn = Color(0xFFA8641B)
                WarnDim = Color(0xFF7D4B14)
                WarnBg = Color(0x14A8641B)
                Danger = Color(0xFFB23A2C)
                Link = Color(0xFF1E63A5)
            }
        }
    }

    /** Sets a custom accent color (or resets to the default green when null). */
    fun setAccent(color: Color?) {
        Accent = color ?: DefaultAccent
    }

    /** Mirrors a Material3 [ColorScheme] (e.g. Youniversal) into the Bony palette so legacy screens
     *  that read `BonyColors.*` still look correct when the Youniversal engine is active. */
    fun applyYouniversal(scheme: ColorScheme) {
        Bg = scheme.background
        Surface = scheme.surface
        SurfaceAlt = scheme.surfaceContainer
        Rule = scheme.outlineVariant
        RuleStrong = scheme.outline
        Text = scheme.onBackground
        TextDim = scheme.onSurfaceVariant
        TextMute = scheme.onSurfaceVariant.copy(alpha = 0.6f)
        // Map Youniversal primary/tertiary/error to Bony's semantic roles
        Accent = scheme.primary
        Danger = scheme.error
        Link = scheme.primary
        Warn = scheme.tertiary
        WarnDim = scheme.onTertiaryContainer
        WarnBg = scheme.tertiaryContainer.copy(alpha = 0.12f)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = a.alpha + (b.alpha - a.alpha) * t,
    )

// JetBrains Mono NL (No Ligatures), OFL license — https://www.jetbrains.com/lp/mono/
val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object BonyType {
    val wordmark = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,     fontSize = 30.sp, lineHeight = 30.sp, letterSpacing = (-1.0).sp)
    val title    = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold,     fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp)
    val body     = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal,   fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp)
    val bodyDim  = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp)
    val meta     = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal,   fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp)
    val metaDim  = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
    val caption  = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal,   fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp)
    val tag      = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.0.sp)
    val button   = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 1.0.sp)
}

/**
 * Applies the current theme mode + accent, then provides the Material theme.
 *
 * Reads the live BonyColors state during composition, so changing mode/accent
 * anywhere recomposes the whole tree with the new palette.
 */
@Composable
fun BonyTheme(
    mode: ThemeMode = ThemeMode.DARK,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    remember(mode, accent) {
        BonyColors.applyMode(mode)
        BonyColors.setAccent(accent)
    }
    val scheme = if (mode.isDark) {
        darkColorScheme(
            background = BonyColors.Bg,
            surface = BonyColors.Surface,
            surfaceVariant = BonyColors.SurfaceAlt,
            onBackground = BonyColors.Text,
            onSurface = BonyColors.Text,
            onSurfaceVariant = BonyColors.TextDim,
            outline = BonyColors.Rule,
            outlineVariant = BonyColors.Rule,
            primary = BonyColors.Accent,
            onPrimary = BonyColors.Bg,
            error = BonyColors.Danger,
            onError = BonyColors.Bg,
        )
    } else {
        lightColorScheme(
            background = BonyColors.Bg,
            surface = BonyColors.Surface,
            surfaceVariant = BonyColors.SurfaceAlt,
            onBackground = BonyColors.Text,
            onSurface = BonyColors.Text,
            onSurfaceVariant = BonyColors.TextDim,
            outline = BonyColors.Rule,
            outlineVariant = BonyColors.Rule,
            primary = BonyColors.Accent,
            onPrimary = BonyColors.Bg,
            error = BonyColors.Danger,
            onError = BonyColors.Bg,
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}

/** Parses "#RRGGBB" (or "#AARRGGBB") into a Compose Color; null on garbage. */
fun parseHexColor(hex: String?): Color? {
    if (hex == null) return null
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6 && cleaned.length != 8) return null
    val value = cleaned.toLongOrNull(16) ?: return null
    return if (cleaned.length == 6) {
        // #RRGGBB — keep each channel in place (no shifting).
        Color(
            red = ((value ushr 16) and 0xFF) / 255f,
            green = ((value ushr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f,
            alpha = 1f,
        )
    } else {
        Color(
            red = ((value ushr 16) and 0xFF) / 255f,
            green = ((value ushr 8) and 0xFF) / 255f,
            blue = (value and 0xFF) / 255f,
            alpha = ((value ushr 24) and 0xFF) / 255f,
        )
    }
}

/** Formats a Color as "#RRGGBB" for persistence. */
fun colorToHex(color: Color): String {
    val r = (color.red * 255).roundToInt().coerceIn(0, 255)
    val g = (color.green * 255).roundToInt().coerceIn(0, 255)
    val b = (color.blue * 255).roundToInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(r, g, b)
}
