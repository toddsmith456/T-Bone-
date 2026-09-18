// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Conversions from plain values to the optional slots Material 3 components expect.
 *
 * Material 3 treats "no slot" and "an empty slot" differently — an empty label still reserves
 * vertical space — so `null` must stay `null` rather than becoming an empty composable.
 */
internal fun youniversalTextSlot(value: String?): (@Composable () -> Unit)? =
    value?.let { text ->
        @Composable { Text(text) }
    }

internal fun youniversalIconSlot(
    icon: ImageVector?,
    contentDescription: String? = null,
): (@Composable () -> Unit)? =
    icon?.let { vector ->
        @Composable { Icon(imageVector = vector, contentDescription = contentDescription) }
    }
