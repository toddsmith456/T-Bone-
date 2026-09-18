// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.pow

/**
 * Color helpers shared across the theme.
 *
 * Youniversal derives secondary treatments (hover fills, scrims, halos) by nudging an existing
 * role rather than hard-coding new hex values, which keeps every palette — including Material You
 * palettes pulled from a wallpaper — internally consistent.
 */

/** Moves [this] toward white by [amount] (0f = unchanged, 1f = white). */
public fun Color.youniversalLighten(amount: Float): Color = lerp(this, Color.White, amount.coerceIn(0f, 1f))

/** Moves [this] toward black by [amount] (0f = unchanged, 1f = black). */
public fun Color.youniversalDarken(amount: Float): Color = lerp(this, Color.Black, amount.coerceIn(0f, 1f))

/** [this] at a fixed [alpha], useful for scrims, halos and hover states. */
public fun Color.youniversalAt(alpha: Float): Color = copy(alpha = alpha.coerceIn(0f, 1f))

/**
 * Picks black or white for content drawn on top of [this], using WCAG relative luminance so the
 * choice tracks perceived brightness rather than a naive channel average.
 */
public fun Color.youniversalReadableContentColor(): Color {
    // 0.179 is the relative-luminance crossover where black text and white text hit the same
    // WCAG contrast ratio, so it is the correct switch-over point.
    return if (youniversalRelativeLuminance() > 0.179f) Color.Black else Color.White
}

/** WCAG 2.1 relative luminance of a color, with the sRGB transfer function applied. */
public fun Color.youniversalRelativeLuminance(): Float {
    fun channel(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/** WCAG 2.1 contrast ratio between two colors, in the 1:1 to 21:1 range. */
public fun Color.youniversalContrastAgainst(other: Color): Float {
    val a = youniversalRelativeLuminance()
    val b = other.youniversalRelativeLuminance()
    val lighter = maxOf(a, b)
    val darker = minOf(a, b)
    return (lighter + 0.05f) / (darker + 0.05f)
}
