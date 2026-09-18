// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing constants shared by the Youniversal components.
 *
 * These are deliberately *not* scaled by the theme's `cornerScale`/`fontScale` so that layouts keep
 * their rhythm when a user changes those preferences.
 */
@Immutable
public object YouniversalMetrics {
    public val SpacingXxs: Dp = 2.dp
    public val SpacingXs: Dp = 4.dp
    public val SpacingSm: Dp = 8.dp
    public val SpacingMd: Dp = 12.dp
    public val SpacingLg: Dp = 16.dp
    public val SpacingXl: Dp = 24.dp
    public val SpacingXxl: Dp = 32.dp

    /** Standard screen gutter. */
    public val ScreenPadding: Dp = 16.dp

    /** Height of buttons, text fields and segmented controls. */
    public val ControlHeight: Dp = 52.dp

    /** Height of the top bar and the floating bottom bar. */
    public val BarHeight: Dp = 64.dp

    /** Tonal elevation applied to cards. Small on purpose: Youniversal separates with tone, not shadow. */
    public val CardTonalElevation: Dp = 1.dp

    /** Shadow elevation for genuinely floating elements (FABs, the bottom bar, dialogs). */
    public val FloatingElevation: Dp = 6.dp

    /** Hairline stroke used by outlined surfaces. */
    public val Hairline: Dp = 1.dp
}
