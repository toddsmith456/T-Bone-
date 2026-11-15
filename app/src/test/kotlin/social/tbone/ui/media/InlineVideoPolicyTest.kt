package social.tbone.ui.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality

class InlineVideoPolicyTest {
    @Test fun `load and play starts on first reveal and pauses for viewer`() {
        val mode = VideoPlaybackMode.LOAD_ON_TAP
        assertFalse(shouldPlayInlineVideo(mode, false, false, false))
        assertTrue(shouldPlayInlineVideo(mode, true, false, false))
        assertFalse(shouldPlayInlineVideo(mode, true, false, true))
    }

    @Test fun `prepared video starts on tap and pauses for viewer`() {
        val mode = VideoPlaybackMode.PLAY_ON_TAP
        assertFalse(shouldPlayInlineVideo(mode, false, false, false))
        assertTrue(shouldPlayInlineVideo(mode, false, true, false))
        assertFalse(shouldPlayInlineVideo(mode, false, true, true))
    }

    @Test fun `autoplay does not play behind the viewer`() {
        val mode = VideoPlaybackMode.PLAY_IMMEDIATELY
        assertTrue(shouldPlayInlineVideo(mode, false, false, false))
        assertFalse(shouldPlayInlineVideo(mode, false, false, true))
    }

    @Test fun `shouldMountInlineVideo respects mode quality viewer and ai veil`() {
        // Default LOAD_ON_TAP waits for tap
        assertFalse(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.LOAD_ON_TAP, revealed = false, viewerOpen = false))
        assertTrue(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.LOAD_ON_TAP, revealed = true, viewerOpen = false))

        // PLAY_ON_TAP and PLAY_IMMEDIATELY mount immediately when quality is on
        assertTrue(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_ON_TAP, revealed = false, viewerOpen = false))
        assertTrue(shouldMountInlineVideo(VideoQuality.LOW, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = false, viewerOpen = false))

        // VideoQuality.OFF waits for explicit tap regardless of playback mode
        assertFalse(shouldMountInlineVideo(VideoQuality.OFF, VideoPlaybackMode.PLAY_ON_TAP, revealed = false, viewerOpen = false))
        assertFalse(shouldMountInlineVideo(VideoQuality.OFF, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = false, viewerOpen = false))
        assertTrue(shouldMountInlineVideo(VideoQuality.OFF, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = true, viewerOpen = false))

        // AI poster screening holds placeholder until tapped/revealed
        assertFalse(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = false, viewerOpen = false, aiRequiresPlaceholder = true))
        assertTrue(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = true, viewerOpen = false, aiRequiresPlaceholder = true))

        // Fullscreen viewer always unmounts inline player
        assertFalse(shouldMountInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, revealed = true, viewerOpen = true))
    }

    @Test fun `shouldAutoClaimInlineVideo only auto-claims in autoplay mode when unpaused`() {
        assertTrue(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, userPaused = false, viewerOpen = false))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, userPaused = true, viewerOpen = false))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, userPaused = false, viewerOpen = true))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.OFF, VideoPlaybackMode.PLAY_IMMEDIATELY, userPaused = false, viewerOpen = false))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_IMMEDIATELY, userPaused = false, viewerOpen = false, aiRequiresPlaceholder = true))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.PLAY_ON_TAP, userPaused = false, viewerOpen = false))
        assertFalse(shouldAutoClaimInlineVideo(VideoQuality.REGULAR, VideoPlaybackMode.LOAD_ON_TAP, userPaused = false, viewerOpen = false))
    }

    @Test fun `VideoPlaybackMode defaults to LOAD_ON_TAP and round-trips prefs`() {
        assertEquals(VideoPlaybackMode.LOAD_ON_TAP, VideoPlaybackMode.fromPref(null))
        assertEquals(VideoPlaybackMode.LOAD_ON_TAP, VideoPlaybackMode.fromPref("unknown"))
        for (mode in VideoPlaybackMode.entries) {
            assertEquals(mode, VideoPlaybackMode.fromPref(mode.prefValue))
        }
    }
}
