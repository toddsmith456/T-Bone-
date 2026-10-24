package social.tbone.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the v0.4.1 video-upload bug: a hard 10 MB client cap
 * rejected essentially every phone video before it reached the transcoder.
 */
class VideoUploadPolicyTest {

    @Test
    fun aTwelveMegabyteClipIsNoLongerRejected() {
        // The old ceiling was 10_000_000 bytes; a 12 MB clip failed instantly.
        assertTrue(VideoUploadPolicy.isWithinCeiling(12_000_000L))
        // 1080p phone clips routinely land between 20 MB and 200 MB.
        assertTrue(VideoUploadPolicy.isWithinCeiling(200L * 1024L * 1024L))
    }

    @Test
    fun absurdFilesAreStillGuarded() {
        assertTrue(VideoUploadPolicy.isWithinCeiling(VideoUploadPolicy.MAX_BYTES))
        assertFalse(VideoUploadPolicy.isWithinCeiling(VideoUploadPolicy.MAX_BYTES + 1L))
        assertEquals(500L * 1024L * 1024L, VideoUploadPolicy.MAX_BYTES)
    }

    @Test
    fun smallClipsAreUploadedUntouched() {
        // Re-encoding 480p/720p costs quality and can grow the file.
        assertFalse(VideoUploadPolicy.needsTranscode(480))
        assertFalse(VideoUploadPolicy.needsTranscode(720))
        assertFalse(VideoUploadPolicy.needsTranscode(VideoUploadPolicy.TARGET_HEIGHT))
    }

    @Test
    fun tallClipsAreDownscaledAndUnknownHeightsAreTried() {
        assertTrue(VideoUploadPolicy.needsTranscode(1920))
        assertTrue(VideoUploadPolicy.needsTranscode(2160))
        assertTrue(VideoUploadPolicy.needsTranscode(0))
    }

    @Test
    fun aSmallerTranscodeIsPreferred() {
        assertTrue(VideoUploadPolicy.preferTranscoded(transcodedBytes = 8L, originalBytes = 40L))
        // Unknown original size: keep the finished transcode rather than discarding it.
        assertTrue(VideoUploadPolicy.preferTranscoded(transcodedBytes = 8L, originalBytes = -1L))
    }

    @Test
    fun anInflatedTranscodeFallsBackToTheOriginal() {
        assertFalse(VideoUploadPolicy.preferTranscoded(transcodedBytes = 40L, originalBytes = 8L))
        assertFalse(VideoUploadPolicy.preferTranscoded(transcodedBytes = 0L, originalBytes = 8L))
    }
}
