package social.tbone.ui.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.settings.VideoPlaybackMode

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
}
