package social.tbone.ui.media

import social.tbone.settings.VideoPlaybackMode

/** A deliberate first tap starts inline playback; opening the viewer pauses it. */
internal fun shouldPlayInlineVideo(
    mode: VideoPlaybackMode,
    revealed: Boolean,
    started: Boolean,
    viewerOpen: Boolean,
): Boolean = !viewerOpen && when (mode) {
    VideoPlaybackMode.PLAY_IMMEDIATELY -> true
    VideoPlaybackMode.PLAY_ON_TAP -> started
    VideoPlaybackMode.LOAD_ON_TAP -> revealed
}
