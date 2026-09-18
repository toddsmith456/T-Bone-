// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Buttons.
 *
 * Every variant is a pill, uses the theme's own color roles, and supports a `loading` state that
 * swaps the leading icon for a spinner without changing the button's size — so a form does not jump
 * when a request starts.
 */

private val ButtonContentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)

@Composable
private fun ButtonRow(
    text: String,
    loading: Boolean,
    leadingIcon: ImageVector?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(YouniversalMetrics.SpacingSm),
    ) {
        when {
            loading ->
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LocalContentColor.current,
                    strokeWidth = 2.dp,
                )

            leadingIcon != null ->
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** The primary action: solid accent fill. */
@Composable
public fun YouniversalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = colors,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonRow(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

/** Fully custom content in a filled pill button. */
@Composable
public fun YouniversalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/** A secondary action with an accent-tinted fill and accent-colored label. */
@Composable
public fun YouniversalTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = colors,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonRow(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

/** A tertiary action: a hairline outline, no fill. */
@Composable
public fun YouniversalOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = colors,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonRow(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

/** A low-emphasis action: label only. */
@Composable
public fun YouniversalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ButtonRow(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}

/** A primary action that needs to float above other content. */
@Composable
public fun YouniversalElevatedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: ImageVector? = null,
    shape: Shape = YouniversalShapes.Pill,
    colors: ButtonColors = ButtonDefaults.elevatedButtonColors(),
) {
    ElevatedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled && !loading,
        shape = shape,
        colors = colors,
        contentPadding = ButtonContentPadding,
    ) {
        ButtonRow(text = text, loading = loading, leadingIcon = leadingIcon)
    }
}
