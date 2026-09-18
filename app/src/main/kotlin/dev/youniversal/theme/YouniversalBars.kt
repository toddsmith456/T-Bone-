// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.youniversal.theme

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * App bars, navigation and floating actions.
 *
 * The bottom bar floats: a rounded pill lifted off the backdrop with a hairline edge, sitting clear
 * of the gesture area, instead of a full-width bar welded to the bottom of the screen.
 */

/** A transparent, center-aligned top bar. Give it a scroll behavior to have it condense on scroll. */
@Composable
public fun YouniversalTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = Color.Transparent,
) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon,
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
            ),
        scrollBehavior = scrollBehavior,
    )
}

/** A circular icon button, optionally with a filled container for use inside bars. */
@Composable
public fun YouniversalIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = Color.Transparent,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Surface(
            shape = YouniversalShapes.Pill,
            color = containerColor,
            contentColor = tint,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.padding(YouniversalMetrics.SpacingSm).size(22.dp),
            )
        }
    }
}

/**
 * The floating pill navigation bar.
 *
 * Wrap [YouniversalNavigationItem]s inside it, exactly as you would with Material 3's
 * `NavigationBar`.
 */
@Composable
public fun YouniversalBottomBar(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp),
    horizontalPadding: Dp = YouniversalMetrics.SpacingLg,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier =
            modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = horizontalPadding, vertical = 10.dp),
        shape = shape,
        color = containerColor,
        tonalElevation = YouniversalMetrics.CardTonalElevation,
        shadowElevation = YouniversalMetrics.FloatingElevation,
        border = youniversalCardBorder(),
    ) {
        NavigationBar(
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            content = content,
        )
    }
}

/** A [YouniversalBottomBar] item. */
@Composable
public fun RowScope.YouniversalNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showLabel: Boolean = true,
    // Material 3 1.4.0 offers only the no-argument item-colour factory; its defaults are
    // already onSecondaryContainer / secondaryContainer / onSurfaceVariant.
    colors: NavigationBarItemColors = NavigationBarItemDefaults.colors(),
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = icon, contentDescription = null) },
        modifier = modifier,
        enabled = enabled,
        label = if (showLabel) {
            { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        alwaysShowLabel = showLabel,
        colors = colors,
    )
}

/** Circular floating action button. */
@Composable
public fun YouniversalFab(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        containerColor = containerColor,
        contentColor = contentColor,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** Floating action button with a label, for the primary action of a screen. */
@Composable
public fun YouniversalExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    ExtendedFloatingActionButton(
        text = { Text(text) },
        icon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick,
        modifier = modifier,
        shape = YouniversalShapes.Pill,
        containerColor = containerColor,
        contentColor = contentColor,
    )
}
