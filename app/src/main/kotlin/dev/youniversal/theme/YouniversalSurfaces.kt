// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Surfaces: the containers Youniversal content sits in.
 *
 * They are built on [Surface] rather than `Card` so that shape, tonal elevation and border are all
 * explicit — Youniversal separates layers with tone and a hairline edge, not with drop shadows.
 */

/** The default card edge: a hairline of `outlineVariant`, softened so it never reads as a box. */
@Composable
public fun youniversalCardBorder(): BorderStroke =
    BorderStroke(
        width = YouniversalMetrics.Hairline,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
    )

/** A quiet, tonally elevated container. The workhorse surface. */
@Composable
public fun YouniversalCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = YouniversalMetrics.CardTonalElevation,
    border: BorderStroke? = youniversalCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        border = border,
    ) {
        Column(content = content)
    }
}

/** A tappable [YouniversalCard] with Material ripple and a lifted shadow while enabled. */
@Composable
public fun YouniversalCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = YouniversalMetrics.CardTonalElevation,
    border: BorderStroke? = youniversalCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = if (enabled) 2.dp else 0.dp,
        border = border,
    ) {
        Column(content = content)
    }
}

/** A container lifted off the page with a soft shadow, for content that overlaps other content. */
@Composable
public fun YouniversalElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    tonalElevation: Dp = YouniversalMetrics.CardTonalElevation,
    shadowElevation: Dp = YouniversalMetrics.FloatingElevation,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = tonalElevation,
        shadowElevation = shadowElevation,
    ) {
        Column(content = content)
    }
}

/** A bordered container with no fill change, for dense or nested content. */
@Composable
public fun YouniversalOutlinedCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke = youniversalCardBorder(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Column(content = content)
    }
}

/**
 * A statement surface with an accent gradient wash behind its content.
 *
 * The wash is built from the theme's own `primary` and `tertiary` at low alpha, so it stays correct
 * in every background theme — including a Material You palette pulled from the wallpaper.
 */
@Composable
public fun YouniversalHeroCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    gradientStart: Color = MaterialTheme.colorScheme.primary,
    gradientEnd: Color = MaterialTheme.colorScheme.tertiary,
    washAlpha: Float = 0.14f,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: Dp = YouniversalMetrics.SpacingXl,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = YouniversalMetrics.CardTonalElevation,
        border = youniversalCardBorder(),
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .drawBehind {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                gradientStart.copy(alpha = washAlpha),
                                gradientEnd.copy(alpha = washAlpha * 0.45f),
                                Color.Transparent,
                            ),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                },
        ) {
            Column(modifier = Modifier.padding(contentPadding), content = content)
        }
    }
}
