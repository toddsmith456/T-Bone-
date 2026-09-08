package social.tbone.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import social.tbone.settings.ImageLoadMode

/**
 * The current inline-image preference, available to any note-rendering
 * composable in the tree without threading it through every call site.
 *
 * Provided at the nav-host level (see BonyNavHost); defaults to OFF so
 * anything rendered outside the provider keeps the original behavior.
 */
val LocalImageLoadMode = staticCompositionLocalOf { ImageLoadMode.ON }
