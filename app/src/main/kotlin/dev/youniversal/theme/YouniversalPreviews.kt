// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/**
 * Multi-preview annotation: annotating any composable with [YouniversalPreview] renders it in all
 * three background themes in Android Studio's preview pane.
 *
 * ```
 * @YouniversalPreview
 * @Composable
 * private fun MyComponentPreview() {
 *     YouniversalTheme { MyComponent() }
 * }
 * ```
 *
 * The backgrounds match the shipped palettes, so a preview looks the way the running app does.
 */
@Preview(
    name = "Youniversal Light",
    showBackground = true,
    backgroundColor = 0xFFF9F9FD,
    widthDp = 420,
    heightDp = 860,
)
@Preview(
    name = "Youniversal Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
    backgroundColor = 0xFF131316,
    widthDp = 420,
    heightDp = 860,
)
@Preview(
    name = "Youniversal Cream",
    showBackground = true,
    backgroundColor = 0xFFE2D5C7,
    widthDp = 420,
    heightDp = 860,
)
public annotation class YouniversalPreview
