package social.tbone.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality

/** Media preferences available to note cards without threading them through every route. */
val LocalVideoQuality = staticCompositionLocalOf { VideoQuality.REGULAR }
val LocalVideoPlaybackMode = staticCompositionLocalOf { VideoPlaybackMode.PLAY_ON_TAP }
val LocalVideoThumbnails = staticCompositionLocalOf { true }

/**
 * True when inline media frames take the media's real shape.
 *
 * Defaults to true to match [social.tbone.settings.AppSettings.mediaFittedLayout],
 * which is on by default. BonyNavHost overrides this from settings, but the
 * default matters for the first frame(s) before settings have loaded — leaving it
 * false flashed the old fixed-box layout on every cold start.
 */
val LocalMediaFitted = staticCompositionLocalOf { true }
