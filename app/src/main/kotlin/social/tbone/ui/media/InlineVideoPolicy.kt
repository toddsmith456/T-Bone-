package social.tbone.ui.media

import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality

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

/**
 * Whether an inline video should mount its ExoPlayer instead of showing a lightweight placeholder.
 *
 * - [VideoQuality.OFF] always waits for an explicit tap (`revealed == true`).
 * - When the offline AI scanner needs to screen a supplied poster first, stay on the placeholder
 *   until the user taps or reveals it.
 * - Otherwise [VideoPlaybackMode.LOAD_ON_TAP] stays on the placeholder until tapped, while
 *   [VideoPlaybackMode.PLAY_ON_TAP] and [VideoPlaybackMode.PLAY_IMMEDIATELY] mount immediately.
 */
internal fun shouldMountInlineVideo(
    quality: VideoQuality,
    mode: VideoPlaybackMode,
    revealed: Boolean,
    viewerOpen: Boolean,
    aiRequiresPlaceholder: Boolean = false,
): Boolean {
    if (viewerOpen) return false
    if (revealed) return true
    if (quality == VideoQuality.OFF) return false
    if (aiRequiresPlaceholder) return false
    return mode != VideoPlaybackMode.LOAD_ON_TAP
}

/**
 * Whether a visible inline video should automatically claim the single active playback slot
 * (only in [VideoPlaybackMode.PLAY_IMMEDIATELY] when not manually paused, not blocked by
 * [VideoQuality.OFF] or AI poster screening, and not behind the fullscreen viewer).
 */
internal fun shouldAutoClaimInlineVideo(
    quality: VideoQuality,
    mode: VideoPlaybackMode,
    userPaused: Boolean,
    viewerOpen: Boolean,
    aiRequiresPlaceholder: Boolean = false,
): Boolean =
    mode == VideoPlaybackMode.PLAY_IMMEDIATELY &&
        quality != VideoQuality.OFF &&
        !userPaused &&
        !viewerOpen &&
        !aiRequiresPlaceholder
