// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector

/** Chips: pill-shaped, hairline-edged, and tinted from the active theme. */

/** An action or attribute, e.g. "Add to calendar" or "Wi-Fi". */
@Composable
public fun YouniversalAssistChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ChipColors = AssistChipDefaults.assistChipColors(),
) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        leadingIcon = youniversalIconSlot(leadingIcon),
    )
}

/** A filter that can be on or off. */
@Composable
public fun YouniversalFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = YouniversalShapes.Pill,
    colors: SelectableChipColors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text) },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
    )
}

/** A suggestion the user can accept. */
@Composable
public fun YouniversalSuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = YouniversalShapes.Pill,
    colors: ChipColors = SuggestionChipDefaults.suggestionChipColors(),
) {
    SuggestionChip(
        onClick = onClick,
        label = { Text(text) },
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
    )
}
