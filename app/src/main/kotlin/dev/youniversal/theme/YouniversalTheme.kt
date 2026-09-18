// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily

internal val LocalYouniversalEnabled = staticCompositionLocalOf { true }

internal val LocalYouniversalBackgroundStyle =
    staticCompositionLocalOf { YouniversalBackgroundStyle.Light }

/**
 * Read the active Youniversal theme from anywhere inside [YouniversalTheme].
 *
 * ```
 * val isCream = YouniversalTheme.backgroundStyle == YouniversalBackgroundStyle.Cream
 * val success = YouniversalTheme.extendedColors.success
 * ```
 */
public object YouniversalTheme {

    /**
     * False when the theme has been switched off, in which case content is rendered by the host
     * app's own theme and Youniversal components fall back to Material 3 defaults.
     */
    public val enabled: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalYouniversalEnabled.current

    /** The resolved background theme: never [YouniversalBackgroundStyle.Auto]. */
    public val backgroundStyle: YouniversalBackgroundStyle
        @Composable @ReadOnlyComposable
        get() = LocalYouniversalBackgroundStyle.current

    /** True for the Dark theme, false for Light and Cream. */
    public val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalYouniversalBackgroundStyle.current.isDark

    /** Success/warning/info colors, backdrop glows and shimmer colors. */
    public val extendedColors: YouniversalExtendedColors
        @Composable @ReadOnlyComposable
        get() = LocalYouniversalExtendedColors.current
}

/**
 * Applies the Youniversal design system to [content].
 *
 * Wrap an entire app to use it as the whole UI:
 * ```
 * setContent { YouniversalTheme { App() } }
 * ```
 *
 * Or make it an option the user can switch off — with `enabled = false` the content is handed to
 * [fallback] (a plain Material 3 theme by default) and nothing Youniversal-branded is applied:
 * ```
 * YouniversalTheme(enabled = preferences.useYouniversal) { App() }
 * ```
 *
 * @param backgroundStyle [YouniversalBackgroundStyle.Light], [Dark][YouniversalBackgroundStyle.Dark],
 *   [Cream][YouniversalBackgroundStyle.Cream] or [Auto][YouniversalBackgroundStyle.Auto] to follow
 *   the system.
 * @param dynamicColor opt into Material You: on Android 12+ the wallpaper palette is used. In the
 *   Cream theme the wallpaper accents are kept but laid over the Cream neutral ramp, so the theme
 *   still reads as aged paper. Ignored below Android 12.
 * @param contrast raise text and edge contrast above the shipped 4.5:1 baseline.
 * @param accentSeed generate the whole palette from a seed instead of the Youniversal accent. Takes
 *   precedence over [dynamicColor]; pass [Color.Unspecified] (the default) to leave it unused.
 * @param cornerScale multiplies every corner radius in the shape scale.
 * @param fontScale multiplies every type scale size.
 * @param fontFamily override the typeface for the whole scale.
 * @param animateTransitions morph every color role when the theme changes.
 * @param styleSystemBars tint the status and navigation bar icons to match the background.
 * @param enabled master switch. When false, [fallback] renders the content instead.
 * @param fallback the theme used when [enabled] is false. Defaults to a baseline Material 3 theme;
 *   supply your own to hand control back to a host app's existing theme.
 */
@Composable
public fun YouniversalTheme(
    backgroundStyle: YouniversalBackgroundStyle = YouniversalBackgroundStyle.Auto,
    dynamicColor: Boolean = true,
    contrast: YouniversalContrast = YouniversalContrast.Default,
    accentSeed: Color = Color.Unspecified,
    cornerScale: Float = 1f,
    fontScale: Float = 1f,
    fontFamily: FontFamily? = null,
    animateTransitions: Boolean = true,
    styleSystemBars: Boolean = true,
    enabled: Boolean = true,
    fallback: @Composable (@Composable () -> Unit) -> Unit = { content -> MaterialTheme(content = content) },
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedStyle = backgroundStyle.resolve(systemDark)
    val extendedColors = youniversalExtendedColors(resolvedStyle, systemDark)

    if (!enabled) {
        // Switched off: keep the locals valid for any Youniversal component still in the tree, but
        // let the host theme decide colors, type and shape.
        CompositionLocalProvider(
            LocalYouniversalEnabled provides false,
            LocalYouniversalBackgroundStyle provides resolvedStyle,
            LocalYouniversalExtendedColors provides YouniversalExtendedColors.Fallback,
        ) {
            fallback(content)
        }
        return
    }

    val context = LocalContext.current
    val hasCustomSeed = accentSeed != Color.Unspecified

    // Material You: wallpaper-derived scheme, Android 12 and up.
    val dynamicScheme: ColorScheme? =
        if (dynamicColor && !hasCustomSeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (resolvedStyle.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }

    val targetScheme = remember(resolvedStyle, contrast, accentSeed, hasCustomSeed, dynamicScheme, systemDark) {
        val base = when {
            hasCustomSeed -> YouniversalTonalPalette.colorScheme(accentSeed, resolvedStyle.isDark)
            dynamicScheme != null && resolvedStyle == YouniversalBackgroundStyle.Cream ->
                dynamicScheme.withCreamNeutrals()
            dynamicScheme != null -> dynamicScheme
            else -> youniversalColorScheme(resolvedStyle, systemDark)
        }
        if (contrast == YouniversalContrast.High) base.youniversalHighContrast(resolvedStyle.isDark) else base
    }

    val colorScheme =
        if (animateTransitions) rememberAnimatedColorScheme(targetScheme) else targetScheme
    val shapes = remember(cornerScale) { youniversalShapes(cornerScale) }
    val typography = remember(fontFamily, fontScale) { youniversalTypography(fontFamily, fontScale) }

    CompositionLocalProvider(
        LocalYouniversalEnabled provides true,
        LocalYouniversalBackgroundStyle provides resolvedStyle,
        LocalYouniversalExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            typography = typography,
        ) {
            if (styleSystemBars) {
                val darkIcons = colorScheme.background.youniversalRelativeLuminance() > 0.179f
                YouniversalSystemBars(darkIcons = darkIcons)
            }
            content()
        }
    }
}

/**
 * Applies Youniversal from a persisted [YouniversalThemeState], so a settings screen can drive the
 * whole app. Every property of [state] is observable: changing one re-themes the tree immediately.
 */
@Composable
public fun YouniversalTheme(
    state: YouniversalThemeState,
    fallback: @Composable (@Composable () -> Unit) -> Unit = { content -> MaterialTheme(content = content) },
    content: @Composable () -> Unit,
) {
    YouniversalTheme(
        backgroundStyle = state.backgroundStyle,
        dynamicColor = state.dynamicColor,
        contrast = state.contrast,
        accentSeed = state.accentSeed,
        cornerScale = state.cornerScale,
        fontScale = state.fontScale,
        fontFamily = state.fontFamily,
        animateTransitions = state.animateTransitions,
        enabled = state.enabled,
        fallback = fallback,
        content = content,
    )
}
