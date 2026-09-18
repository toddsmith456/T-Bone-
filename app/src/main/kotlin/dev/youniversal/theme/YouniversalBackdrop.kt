// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** How the area behind app content is painted. */
public enum class YouniversalBackdropStyle {
    /** A single flat color. Cheapest; use it on low-end devices or behind dense content. */
    Flat,

    /**
     * Three wide, low-alpha glows drifting off the corners. Reads as depth rather than decoration
     * and stays subtle enough to sit behind text at any size.
     */
    Aurora,

    /** Flat fill with the edges darkened, which pulls focus to the middle of the screen. */
    Vignette,
}

/**
 * The ambient background layer for a Youniversal screen.
 *
 * Place it behind your content — [YouniversalScaffold] does this for you — or use it directly to
 * give any container the same treatment:
 * ```
 * YouniversalBackdrop { Text("Hello") }
 * ```
 */
@Composable
public fun YouniversalBackdrop(
    modifier: Modifier = Modifier,
    style: YouniversalBackdropStyle = YouniversalBackdropStyle.Aurora,
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit,
) {
    val extended = YouniversalTheme.extendedColors
    val scrim = MaterialTheme.colorScheme.scrim

    Box(
        modifier = modifier
            .background(containerColor)
            .drawBehind {
                val width = size.width
                val height = size.height
                val reach = maxOf(width, height)

                when (style) {
                    YouniversalBackdropStyle.Flat -> Unit

                    YouniversalBackdropStyle.Aurora -> {
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(extended.backdropGlowA, Color.Transparent),
                                center = Offset(width * 0.12f, height * 0.02f),
                                radius = reach * 0.85f,
                            ),
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(extended.backdropGlowB, Color.Transparent),
                                center = Offset(width * 0.95f, height * 0.28f),
                                radius = reach * 0.6f,
                            ),
                        )
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(extended.backdropGlowC, Color.Transparent),
                                center = Offset(width * 0.5f, height * 1.05f),
                                radius = reach * 0.7f,
                            ),
                        )
                    }

                    YouniversalBackdropStyle.Vignette ->
                        drawRect(
                            brush = Brush.radialGradient(
                                colors = listOf(Color.Transparent, scrim.copy(alpha = 0.12f)),
                                center = Offset(width * 0.5f, height * 0.45f),
                                radius = reach * 0.78f,
                            ),
                        )
                }
            },
        content = content,
    )
}
