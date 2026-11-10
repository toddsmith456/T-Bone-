// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * One-tap curated looks for the Youniversal engine.
 *
 * A look is a coordinated bundle of the settings that already exist —
 * background, contrast, accent seed, app font, corner radius and text size —
 * so tapping it re-themes the whole app in a single step. The fine controls
 * stay available; changing any bundled control by hand marks the active look
 * as [CUSTOM] (see `YouniversalThemeState.applyLook` / the per-control
 * setters), so nothing locks the user in.
 *
 * [CUSTOM] itself is not a bundle: it only exists so the settings UI can show
 * that the current mix is hand-tuned. Applying it is a no-op.
 */
public enum class YouniversalLook(
    public val label: String,
    public val description: String,
    public val backgroundStyle: YouniversalBackgroundStyle,
    public val contrast: YouniversalContrast,
    /** [Color.Unspecified] means the app's own brand accent. */
    public val accentSeed: Color,
    public val fontOption: YouniversalFontOption,
    public val cornerScale: Float,
    public val fontScale: Float,
) {
    /** Warm cream paper, forest accent, soft rounded font. */
    PAPER(
        label = "Paper",
        description = "Warm, calm, bookish",
        backgroundStyle = YouniversalBackgroundStyle.Cream,
        contrast = YouniversalContrast.Default,
        accentSeed = Color(0xFF1B7F3B),
        fontOption = YouniversalFontOption.SOFT_ROUNDED,
        cornerScale = 1.2f,
        fontScale = 1f,
    ),

    /** Deep charcoal night theme, teal accent. */
    MIDNIGHT(
        label = "Midnight",
        description = "Deep, focused, night-friendly",
        backgroundStyle = YouniversalBackgroundStyle.Dark,
        contrast = YouniversalContrast.Default,
        accentSeed = Color(0xFF0F9C8E),
        fontOption = YouniversalFontOption.STANDARD,
        cornerScale = 1f,
        fontScale = 1f,
    ),

    /** Cool near-white light theme, violet accent, crisper corners. */
    PORCELAIN(
        label = "Porcelain",
        description = "Crisp, clean, professional",
        backgroundStyle = YouniversalBackgroundStyle.Light,
        contrast = YouniversalContrast.Default,
        accentSeed = Color(0xFF6A3FB5),
        fontOption = YouniversalFontOption.STANDARD,
        cornerScale = 0.7f,
        fontScale = 1f,
    ),

    /** Follows the system light/dark setting, rose accent, softest corners. */
    ROSEWOOD(
        label = "Rosewood",
        description = "Friendly, personal, social",
        backgroundStyle = YouniversalBackgroundStyle.Auto,
        contrast = YouniversalContrast.Default,
        accentSeed = Color(0xFFC2185B),
        fontOption = YouniversalFontOption.SOFT_ROUNDED,
        cornerScale = 1.4f,
        fontScale = 1f,
    ),

    /** Follows the system, raised contrast, larger text — the legibility look. */
    SIGNAL(
        label = "Signal",
        description = "Maximum legibility",
        backgroundStyle = YouniversalBackgroundStyle.Auto,
        contrast = YouniversalContrast.High,
        accentSeed = Color.Unspecified,
        fontOption = YouniversalFontOption.STANDARD,
        cornerScale = 0.8f,
        fontScale = 1.15f,
    ),

    /**
     * Hand-tuned mix. Not a bundle — its field values are inert placeholders
     * so the enum can carry a uniform shape; [isBundle] is false.
     */
    CUSTOM(
        label = "Custom",
        description = "Your own fine-tuned mix",
        backgroundStyle = YouniversalBackgroundStyle.Auto,
        contrast = YouniversalContrast.Default,
        accentSeed = Color.Unspecified,
        fontOption = YouniversalFontOption.STANDARD,
        cornerScale = 1f,
        fontScale = 1f,
    ),
    ;

    /** True when this entry carries an applyable settings bundle. */
    public val isBundle: Boolean
        get() = this != CUSTOM
}

/**
 * Resolves the full [ColorScheme] for a set of Youniversal preferences using
 * the same recipe as [YouniversalTheme]: seed first (with cream neutrals on
 * the Cream background), then wallpaper colours when offered, then contrast.
 *
 * [dynamicScheme] is an optional factory for Material You wallpaper colours
 * keyed by resolved darkness; pass `null` when wallpaper colours are off.
 */
public fun youniversalScheme(
    backgroundStyle: YouniversalBackgroundStyle,
    contrast: YouniversalContrast,
    accentSeed: Color,
    isSystemDark: Boolean,
    dynamicScheme: ((Boolean) -> ColorScheme)? = null,
): ColorScheme {
    val resolved = backgroundStyle.resolve(isSystemDark)
    val hasSeed = accentSeed != Color.Unspecified
    val wallpaper = if (hasSeed) null else dynamicScheme?.invoke(resolved.isDark)
    val base = when {
        hasSeed -> {
            val seeded = YouniversalTonalPalette.colorScheme(
                accentSeed,
                resolved.isDark,
                vividAccents = !resolved.isDark,
            )
            if (resolved == YouniversalBackgroundStyle.Cream) seeded.withCreamNeutrals() else seeded
        }
        wallpaper != null && resolved == YouniversalBackgroundStyle.Cream ->
            wallpaper.withCreamNeutrals()
        wallpaper != null -> wallpaper
        else -> youniversalColorScheme(backgroundStyle, isSystemDark)
    }
    return if (contrast == YouniversalContrast.High) {
        base.youniversalHighContrast(resolved.isDark)
    } else {
        base
    }
}

/** [youniversalScheme] for a [YouniversalLook]'s bundled preferences. */
public fun youniversalLookScheme(
    look: YouniversalLook,
    isSystemDark: Boolean,
    dynamicScheme: ((Boolean) -> ColorScheme)? = null,
): ColorScheme = youniversalScheme(
    backgroundStyle = look.backgroundStyle,
    contrast = look.contrast,
    accentSeed = look.accentSeed,
    isSystemDark = isSystemDark,
    dynamicScheme = dynamicScheme,
)
