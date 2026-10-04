package social.tbone.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality

/** Media preferences available to note cards without threading them through every route. */
val LocalVideoQuality = staticCompositionLocalOf { VideoQuality.REGULAR }
val LocalVideoPlaybackMode = staticCompositionLocalOf { VideoPlaybackMode.PLAY_ON_TAP }
val LocalVideoThumbnails = staticCompositionLocalOf { true }
