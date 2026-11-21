package social.tbone.media.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class AudioPlaybackPolicyTest {
    @Test fun expandingSelectedAudioMustNotResetPausedStateOrQueue() {
        assertTrue(preserveAudioSelection("https://a/song.mp3", "https://a/song.mp3"))
        assertFalse(preserveAudioSelection("https://a/other.mp3", "https://a/song.mp3"))
        assertFalse(preserveAudioSelection(null, "https://a/song.mp3"))
    }
    @Test fun handoffSurvivesSessionTimelineSerializationWithoutTheUri() {
        val key = social.tbone.media.audio.AudioCachePolicy.key("https://a/song.mp3?token=secret")
        assertTrue(preserveAudioSelection(null, "https://a/song.mp3?token=secret", key))
        assertFalse(preserveAudioSelection(null, "https://a/song.mp3?token=different", key))
    }
    @Test fun identityIsStableButTracksAndProvenanceDoNotCollide() {
        assertEquals(stableAudioId("https://a/track", "event"), stableAudioId("https://a/track", "event"))
        assertNotEquals(stableAudioId("https://a/track", "one"), stableAudioId("https://a/track", "two"))
        assertNotEquals(stableAudioId("https://a/track?a=1"), stableAudioId("https://a/track?a=2"))
    }
    @Test fun loadingFailuresAndEndedStatesAreDistinct() {
        assertEquals(AudioPhase.ERROR, audioPhase(false, true, false, Player.STATE_IDLE, false))
        assertEquals(AudioPhase.CONNECTING, audioPhase(false, false, false, Player.STATE_IDLE, false))
        assertEquals(AudioPhase.IDLE, audioPhase(true, false, false, Player.STATE_IDLE, false))
        assertEquals(AudioPhase.PREPARING, audioPhase(true, false, true, Player.STATE_BUFFERING, false))
        assertEquals(AudioPhase.PAUSED, audioPhase(true, false, true, Player.STATE_READY, false))
        assertEquals(AudioPhase.PLAYING, audioPhase(true, false, true, Player.STATE_READY, true))
        assertEquals(AudioPhase.ENDED, audioPhase(true, false, true, Player.STATE_ENDED, false))
    }
    @Test fun identicalOrMissingMetadataDoesNotReplaceThePlayingItem() {
        assertFalse(audioDetailsChanged("Song", "Artist", "Album", byteArrayOf(1,2), "Song", "Artist", "Album", byteArrayOf(1,2)))
        assertFalse(audioDetailsChanged("Song", "Artist", "Album", byteArrayOf(1), null, "", null, null))
        assertTrue(audioDetailsChanged("Song", "Artist", "Album", byteArrayOf(1), "Better title", null, null, null))
        assertTrue(audioDetailsChanged("Song", "Artist", "Album", byteArrayOf(1), null, null, null, byteArrayOf(2)))
    }
    @Test fun queueMovesRespectBoundariesAndRejectStaleIndices() {
        assertNull(audioQueueMoveTarget(0, 3, -1))
        assertNull(audioQueueMoveTarget(2, 3, 1))
        assertNull(audioQueueMoveTarget(-1, 3, 1))
        assertNull(audioQueueMoveTarget(0, 0, 1))
        assertEquals(0, audioQueueMoveTarget(1, 3, -1))
        assertEquals(2, audioQueueMoveTarget(1, 3, 1))
    }
}
