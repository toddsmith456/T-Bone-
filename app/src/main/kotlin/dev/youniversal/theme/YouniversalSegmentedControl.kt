// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * A segmented control with a pill that slides between options.
 *
 * Material 3 ships segmented buttons, but their borders and per-button outlines do not match a
 * system built on tone and radius. This is a single rounded track with one animated indicator, so
 * the selection change reads as motion instead of as two buttons repainting.
 *
 * ```
 * YouniversalSegmentedControl(
 *     options = listOf("Light", "Dark", "Cream"),
 *     selected = current,
 *     onOptionSelected = ::onSelect,
 * )
 * ```
 *
 * Options are laid out at equal widths. Labels should stay short — one or two words — because the
 * control truncates rather than wraps.
 */
@Composable
public fun <T> YouniversalSegmentedControl(
    options: List<T>,
    selected: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = YouniversalShapes.Pill,
    label: @Composable (option: T, isSelected: Boolean) -> Unit = { option, _ ->
        Text(
            text = option.toString(),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
) {
    require(options.isNotEmpty()) { "YouniversalSegmentedControl needs at least one option" }

    val scheme = MaterialTheme.colorScheme
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(YouniversalMotion.DurationMedium, easing = YouniversalMotion.EaseStandard),
        label = "youniversalSegmentedIndicator",
    )
    val trackAlpha = if (enabled) 1f else 0.5f

    BoxWithConstraints(
        modifier =
            modifier
                .height(YouniversalMetrics.ControlHeight - YouniversalMetrics.SpacingMd)
                .clip(shape)
                .background(scheme.surfaceContainerHighest.copy(alpha = trackAlpha)),
    ) {
        val itemWidth = maxWidth / options.size
        val itemWidthPx = constraints.maxWidth.toFloat() / options.size
        val inset = YouniversalMetrics.SpacingXs

        // The indicator: one accent pill, offset by the animated index.
        Box(
            modifier =
                Modifier
                    .padding(inset)
                    .width(itemWidth - inset * 2)
                    .fillMaxHeight()
                    .offset { IntOffset((animatedIndex * itemWidthPx).roundToInt(), 0) }
                    .clip(shape)
                    .background(scheme.primary.copy(alpha = trackAlpha)),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue =
                        when {
                            !enabled -> scheme.onSurfaceVariant
                            isSelected -> scheme.onPrimary
                            else -> scheme.onSurfaceVariant
                        },
                    animationSpec = tween(YouniversalMotion.DurationFast),
                    label = "youniversalSegmentedLabel",
                )
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = Role.Tab,
                                onClick = { onOptionSelected(option) },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        label(option, isSelected)
                    }
                }
            }
        }
    }
}
