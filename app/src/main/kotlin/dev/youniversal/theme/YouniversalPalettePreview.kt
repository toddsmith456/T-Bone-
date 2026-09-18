// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ColorScheme

/**
 * Palette inspection: swatches for the active theme.
 *
 * Useful in a design/QA screen, in screenshots, and when reporting a color bug — every role is
 * labeled with its name and hex.
 */

/** Every color role of [scheme] as `(name, color)` pairs, in Material 3 role order. */
public fun youniversalColorRoles(scheme: ColorScheme): List<Pair<String, Color>> =
    listOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "inversePrimary" to scheme.inversePrimary,
        "secondary" to scheme.secondary,
        "onSecondary" to scheme.onSecondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "onTertiary" to scheme.onTertiary,
        "tertiaryContainer" to scheme.tertiaryContainer,
        "onTertiaryContainer" to scheme.onTertiaryContainer,
        "error" to scheme.error,
        "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer,
        "onErrorContainer" to scheme.onErrorContainer,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "onSurface" to scheme.onSurface,
        "surfaceVariant" to scheme.surfaceVariant,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "surfaceTint" to scheme.surfaceTint,
        "inverseSurface" to scheme.inverseSurface,
        "inverseOnSurface" to scheme.inverseOnSurface,
        "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant,
        "scrim" to scheme.scrim,
        "surfaceBright" to scheme.surfaceBright,
        "surfaceDim" to scheme.surfaceDim,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "primaryFixed" to scheme.primaryFixed,
        "primaryFixedDim" to scheme.primaryFixedDim,
        "onPrimaryFixed" to scheme.onPrimaryFixed,
        "onPrimaryFixedVariant" to scheme.onPrimaryFixedVariant,
        "secondaryFixed" to scheme.secondaryFixed,
        "secondaryFixedDim" to scheme.secondaryFixedDim,
        "onSecondaryFixed" to scheme.onSecondaryFixed,
        "onSecondaryFixedVariant" to scheme.onSecondaryFixedVariant,
        "tertiaryFixed" to scheme.tertiaryFixed,
        "tertiaryFixedDim" to scheme.tertiaryFixedDim,
        "onTertiaryFixed" to scheme.onTertiaryFixed,
        "onTertiaryFixedVariant" to scheme.onTertiaryFixedVariant,
    )

/** A single labeled color chip. */
@Composable
public fun YouniversalColorSwatch(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    showHex: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(MaterialTheme.shapes.medium)
                    .background(color)
                    .border(
                        width = YouniversalMetrics.Hairline,
                        color = scheme.outlineVariant.copy(alpha = 0.7f),
                        shape = MaterialTheme.shapes.medium,
                    ),
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = YouniversalMetrics.SpacingXs),
            maxLines = 1,
        )
        if (showHex) {
            Text(
                text = "#%06X".format(color.toArgb() and 0xFFFFFF),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Every role of the active scheme, two per row. */
@Composable
public fun YouniversalPalettePreview(
    modifier: Modifier = Modifier,
    scheme: ColorScheme = MaterialTheme.colorScheme,
    roles: List<Pair<String, Color>> = youniversalColorRoles(scheme),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd),
    ) {
        roles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingMd)) {
                row.forEach { (name, color) ->
                    YouniversalColorSwatch(name = name, color = color, modifier = Modifier.weight(1f))
                }
                // Keep the last row aligned when there is an odd number of roles.
                if (row.size == 1) Box(modifier = Modifier.weight(1f))
            }
        }
    }
}
