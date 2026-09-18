// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Youniversal's shape scale.
 *
 * Everything is generously rounded: 8dp for the smallest chrome up to 32dp for sheets and dialogs,
 * with a pill shape reserved for buttons, chips and segmented controls.
 */
@Immutable
public object YouniversalShapes {
    public val ExtraSmall: Dp = 8.dp
    public val Small: Dp = 12.dp
    public val Medium: Dp = 18.dp
    public val Large: Dp = 24.dp
    public val ExtraLarge: Dp = 32.dp

    /** Fully rounded; used by buttons, chips, segmented controls and the floating bottom bar. */
    public val Pill: Shape = RoundedCornerShape(percent = 50)
}

/**
 * Builds the Material 3 [Shapes] scale for Youniversal.
 *
 * @param cornerScale multiplies every corner radius, so an app (or a user preference) can dial the
 *   roundness up or down without re-authoring the scale. Pass `0f` for a square-cornered look.
 */
public fun youniversalShapes(cornerScale: Float = 1f): Shapes {
    val scale = cornerScale.coerceAtLeast(0f)
    fun scaled(size: Dp): Dp = size * scale
    return Shapes(
        extraSmall = RoundedCornerShape(scaled(YouniversalShapes.ExtraSmall)),
        small = RoundedCornerShape(scaled(YouniversalShapes.Small)),
        medium = RoundedCornerShape(scaled(YouniversalShapes.Medium)),
        large = RoundedCornerShape(scaled(YouniversalShapes.Large)),
        extraLarge = RoundedCornerShape(scaled(YouniversalShapes.ExtraLarge)),
    )
}
