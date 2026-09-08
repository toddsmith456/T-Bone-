package social.tbone.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import social.tbone.settings.AvatarMode

/**
 * The current avatar preferences, available to any composable in the tree
 * without threading them through every call site. Provided at the nav-host
 * level (see BonyNavHost); defaults match the app-wide defaults.
 */
val LocalAvatarMode = staticCompositionLocalOf { AvatarMode.REGULAR }

/** True when GIF avatars are allowed to animate (off = show a still picture). */
val LocalAnimatedAvatars = staticCompositionLocalOf { true }
