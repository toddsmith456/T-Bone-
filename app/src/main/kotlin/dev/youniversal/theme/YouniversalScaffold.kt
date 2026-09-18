// SPDX-License-Identifier: MIT
package dev.youniversal.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A [Scaffold] with the Youniversal ambient backdrop drawn behind it.
 *
 * The scaffold itself is transparent so the [YouniversalBackdrop] shows through behind the top bar,
 * the content and the navigation bar, while content still receives the correct insets padding.
 *
 * ```
 * YouniversalScaffold(
 *     topBar = { YouniversalTopBar(title = "Overview") },
 *     bottomBar = { YouniversalBottomBar { /* items */ } },
 * ) { padding -> Screen(Modifier.padding(padding)) }
 * ```
 */
@Composable
public fun YouniversalScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars,
    backdropStyle: YouniversalBackdropStyle = YouniversalBackdropStyle.Aurora,
    containerColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(modifier = modifier) {
        YouniversalBackdrop(
            modifier = Modifier.fillMaxSize(),
            style = backdropStyle,
            containerColor = containerColor,
        ) {}

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            floatingActionButtonPosition = floatingActionButtonPosition,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}
