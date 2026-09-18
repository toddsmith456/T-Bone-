// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Color roles Material 3 does not define, but real products always need.
 *
 * Everything here is provided through [LocalYouniversalExtendedColors] by [YouniversalTheme] and
 * is readable anywhere as `YouniversalTheme.extendedColors`.
 */
@Immutable
public data class YouniversalExtendedColors(
    /** Positive/confirmation semantics. */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,

    /** Caution semantics. */
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,

    /** Informational semantics. */
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,

    /** Ambient backdrop gradient stops, drawn behind app content by [YouniversalBackdrop]. */
    val backdropBase: Color,
    val backdropGlowA: Color,
    val backdropGlowB: Color,
    val backdropGlowC: Color,

    /** Skeleton/shimmer loading treatment. */
    val shimmerBase: Color,
    val shimmerHighlight: Color,

    /** Soft accent halo used by hero surfaces. */
    val glow: Color,
) {
    public companion object {
        /** Safe values used when content is rendered outside of a [YouniversalTheme]. */
        public val Fallback: YouniversalExtendedColors =
            YouniversalExtendedColors(
                success = Color(0xFF1E8E3E),
                onSuccess = Color.White,
                successContainer = Color(0xFFC8F0D2),
                onSuccessContainer = Color(0xFF00390F),
                warning = Color(0xFFB26A00),
                onWarning = Color.White,
                warningContainer = Color(0xFFFFE0B2),
                onWarningContainer = Color(0xFF3A2200),
                info = Color(0xFF1667C4),
                onInfo = Color.White,
                infoContainer = Color(0xFFD6E5FF),
                onInfoContainer = Color(0xFF001B3D),
                backdropBase = Color(0xFFF9F9FD),
                backdropGlowA = Color(0x143E63DD),
                backdropGlowB = Color(0x0F0F9C8E),
                backdropGlowC = Color(0x00000000),
                shimmerBase = Color(0x14000000),
                shimmerHighlight = Color(0x2EFFFFFF),
                glow = Color(0x333E63DD),
            )
    }
}

/**
 * The extended color roles for [style].
 *
 * Semantic colors are hand-tuned per theme so they keep their meaning while staying in family with
 * the palette: greens stay green, but on Cream they lean olive and on Dark they lift in tone.
 */
public fun youniversalExtendedColors(
    style: YouniversalBackgroundStyle,
    isSystemInDarkTheme: Boolean = false,
): YouniversalExtendedColors =
    when (style.resolve(isSystemInDarkTheme)) {
        YouniversalBackgroundStyle.Dark ->
            YouniversalExtendedColors(
                success = Color(0xFF6FDB94),
                onSuccess = Color(0xFF00390F),
                successContainer = Color(0xFF12542A),
                onSuccessContainer = Color(0xFFC8F0D2),
                warning = Color(0xFFFFC46B),
                onWarning = Color(0xFF3A2200),
                warningContainer = Color(0xFF5B3A00),
                onWarningContainer = Color(0xFFFFE0B2),
                info = Color(0xFF9CC5FF),
                onInfo = Color(0xFF001B3D),
                infoContainer = Color(0xFF13407A),
                onInfoContainer = Color(0xFFD6E5FF),
                backdropBase = Color(0xFF131316),
                backdropGlowA = Color(0x24C1C1FE),
                backdropGlowB = Color(0x1464D9C9),
                backdropGlowC = Color(0x00000000),
                shimmerBase = Color(0x1FFFFFFF),
                shimmerHighlight = Color(0x33FFFFFF),
                glow = Color(0x3DC1C1FE),
            )

        YouniversalBackgroundStyle.Cream ->
            YouniversalExtendedColors(
                success = Color(0xFF3F6B33),
                onSuccess = Color(0xFFF6EFDF),
                successContainer = Color(0xFFD7E3BE),
                onSuccessContainer = Color(0xFF16260C),
                warning = Color(0xFF8A5A16),
                onWarning = Color(0xFFFFF6E6),
                warningContainer = Color(0xFFEEDCBB),
                onWarningContainer = Color(0xFF33210A),
                info = Color(0xFF2C5D7A),
                onInfo = Color(0xFFF1F6FA),
                infoContainer = Color(0xFFD3E2EA),
                onInfoContainer = Color(0xFF0E2431),
                backdropBase = Color(0xFFE2D5C7),
                backdropGlowA = Color(0x1A984723),
                backdropGlowB = Color(0x14576418),
                backdropGlowC = Color(0x00000000),
                shimmerBase = Color(0x123B2F1F),
                shimmerHighlight = Color(0x26FFFCF6),
                glow = Color(0x33984723),
            )

        YouniversalBackgroundStyle.Light,
        YouniversalBackgroundStyle.Auto,
        -> YouniversalExtendedColors(
            success = Color(0xFF1E8E3E),
            onSuccess = Color.White,
            successContainer = Color(0xFFC8F0D2),
            onSuccessContainer = Color(0xFF00390F),
            warning = Color(0xFF9A5B00),
            onWarning = Color.White,
            warningContainer = Color(0xFFFFE0B2),
            onWarningContainer = Color(0xFF331E00),
            info = Color(0xFF1667C4),
            onInfo = Color.White,
            infoContainer = Color(0xFFD6E5FF),
            onInfoContainer = Color(0xFF001B3D),
            backdropBase = Color(0xFFF9F9FD),
            backdropGlowA = Color(0x1A3E63DD),
            backdropGlowB = Color(0x120F9C8E),
            backdropGlowC = Color(0x00000000),
            shimmerBase = Color(0x0F000000),
            shimmerHighlight = Color(0x2EFFFFFF),
            glow = Color(0x333E63DD),
        )
    }

internal val LocalYouniversalExtendedColors =
    staticCompositionLocalOf { YouniversalExtendedColors.Fallback }
