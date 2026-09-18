// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * The Youniversal type scale.
 *
 * Sizes follow the Material 3 scale so existing components keep their rhythm, but the weights and
 * tracking are retuned: display/headline steps use medium weight with negative tracking for a
 * tighter, more editorial feel, and body steps get generous line height for long-form reading.
 *
 * @param fontFamily applied to every style. `null` keeps the platform default, which means the
 *   theme inherits a user-selected system font (and a font downloaded through the AndroidX
 *   `ui-text-google-fonts` artifact if the host app provides one).
 * @param fontScale multiplies every size *and* line height, leaving tracking alone so scaled text
 *   does not become loose.
 */
public fun youniversalTypography(
    fontFamily: FontFamily? = null,
    fontScale: Float = 1f,
): Typography {
    val scale = fontScale.coerceIn(0.7f, 2.5f)

    fun size(value: Int): TextUnit = (value.sp * scale)

    fun style(
        fontSize: Int,
        lineHeight: Int,
        fontWeight: FontWeight,
        letterSpacing: Float = 0f,
    ): TextStyle =
        TextStyle(
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = size(fontSize),
            lineHeight = size(lineHeight),
            letterSpacing = letterSpacing.sp,
        )

    return Typography(
        displayLarge = style(57, 64, FontWeight.Medium, -0.25f),
        displayMedium = style(45, 52, FontWeight.Medium, -0.2f),
        displaySmall = style(36, 44, FontWeight.Medium, -0.15f),
        headlineLarge = style(32, 40, FontWeight.Medium, -0.1f),
        headlineMedium = style(28, 36, FontWeight.Medium, -0.1f),
        headlineSmall = style(24, 32, FontWeight.Medium, 0f),
        titleLarge = style(22, 28, FontWeight.SemiBold, 0f),
        titleMedium = style(16, 22, FontWeight.SemiBold, 0.1f),
        titleSmall = style(14, 20, FontWeight.Medium, 0.1f),
        bodyLarge = style(16, 25, FontWeight.Normal, 0.15f),
        bodyMedium = style(14, 22, FontWeight.Normal, 0.15f),
        bodySmall = style(12, 18, FontWeight.Normal, 0.2f),
        labelLarge = style(14, 20, FontWeight.SemiBold, 0.1f),
        labelMedium = style(12, 16, FontWeight.Medium, 0.4f),
        labelSmall = style(11, 16, FontWeight.Medium, 0.4f),
    )
}
