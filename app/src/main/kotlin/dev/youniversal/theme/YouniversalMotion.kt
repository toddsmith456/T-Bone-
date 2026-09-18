// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset

/**
 * Youniversal's motion language: short, decelerating and never bouncy.
 *
 * Durations are milliseconds. The curves front-load the movement so a change is visible within a
 * single frame and then settles, which is what makes a theme transition feel "smooth" rather than
 * "slow".
 */
@Immutable
public object YouniversalMotion {
    public const val DurationInstant: Int = 100
    public const val DurationFast: Int = 180
    public const val DurationMedium: Int = 280
    public const val DurationSlow: Int = 420

    /** Theme-to-theme color morph. Long enough to read as intentional, short enough to feel instant. */
    public const val DurationTheme: Int = 480

    /** Material 3 "standard" curve: quick start, long settle. */
    public val EaseStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** For elements entering the screen. */
    public val EaseDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** For elements leaving the screen. */
    public val EaseAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The spec used to morph every color role when the theme changes. */
    public val ThemeColorSpec: FiniteAnimationSpec<Float> =
        tween(durationMillis = DurationTheme, easing = EaseStandard)

    /** Spring used by the segmented control pill and other position animations. */
    public val PositionSpring: FiniteAnimationSpec<DpOffset> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = DpOffset.VisibilityThreshold,
        )

    /** Spring used by progress and other fractional animations. */
    public val FractionSpring: FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = 0.01f,   // the Float threshold animation-core uses
        )
}
