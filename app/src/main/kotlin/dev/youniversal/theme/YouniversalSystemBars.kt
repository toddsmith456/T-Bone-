// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Tints the system bar icons to match the current theme.
 *
 * Youniversal themes the whole window: a Cream background needs dark status icons, a Dark
 * background needs light ones. [YouniversalTheme] calls this automatically unless
 * `styleSystemBars = false`.
 *
 * Call it yourself when you drive [androidx.compose.material3.MaterialTheme] directly.
 *
 * @param darkIcons true for dark icons (light backgrounds), false for light icons.
 */
@Composable
public fun YouniversalSystemBars(
    darkIcons: Boolean,
    darkNavigationIcons: Boolean = darkIcons,
) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = view.context.youniversalFindActivity()?.window ?: return
    SideEffect {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = darkIcons
        controller.isAppearanceLightNavigationBars = darkNavigationIcons
    }
}

/**
 * Walks the [ContextWrapper] chain to the hosting [Activity].
 *
 * A Compose view's context is usually the activity itself, but theme overlays and app-compat
 * wrappers insert extra layers, so a plain cast is not enough.
 */
internal tailrec fun Context.youniversalFindActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.youniversalFindActivity()
        else -> null
    }
