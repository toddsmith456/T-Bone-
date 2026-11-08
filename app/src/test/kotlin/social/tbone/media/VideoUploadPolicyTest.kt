package social.tbone.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the video-upload size/quality rules: the v0.4.1 bug (a
 * hard 10 MB client cap rejected essentially every phone video before it
 * reached the transcoder) must stay fixed, and compression must keep running
 * for every clip rather than only for taller-than-profile sources.
 */
class VideoUploadPolicyTest {

    @Test
    fun aTwelveMegabyteClipIsNoLongerRejected() {
        assertTrue(VideoUploadPolicy.isWithinCeiling(12_000_000L))
        assertTrue(VideoUploadPolicy.isWithinCeiling(200L * 1024L * 1024L))
    }

    @Test
    fun absurdFilesAreStillGuarded() {
        assertTrue(VideoUploadPolicy.isWithinCeiling(VideoUploadPolicy.MAX_BYTES))
        assertFalse(VideoUploadPolicy.isWithinCeiling(VideoUploadPolicy.MAX_BYTES + 1L))
        assertEquals(500L * 1024L * 1024L, VideoUploadPolicy.MAX_BYTES)
    }

    @Test
    fun shortClipsAreReEncodedWithoutBeingStretched() {
        assertFalse(VideoUploadPolicy.shouldDownscale(480))
        assertFalse(VideoUploadPolicy.shouldDownscale(720))
        assertFalse(VideoUploadPolicy.shouldDownscale(VideoUploadPolicy.TARGET_HEIGHT))
    }

    @Test
    fun tallClipsAreDownscaled() {
        assertTrue(VideoUploadPolicy.shouldDownscale(1920))
        assertTrue(VideoUploadPolicy.shouldDownscale(2160))
    }

    @Test
    fun unknownHeightsAreNotBlindlyResized() {
        assertFalse(VideoUploadPolicy.shouldDownscale(0))
    }

    @Test
    fun aSmallerTranscodeIsPreferred() {
        assertTrue(VideoUploadPolicy.preferTranscoded(transcodedBytes = 8L, originalBytes = 40L))
        assertTrue(VideoUploadPolicy.preferTranscoded(transcodedBytes = 8L, originalBytes = -1L))
    }

    @Test
    fun anInflatedTranscodeFallsBackToTheOriginal() {
        assertFalse(VideoUploadPolicy.preferTranscoded(transcodedBytes = 40L, originalBytes = 8L))
        assertFalse(VideoUploadPolicy.preferTranscoded(transcodedBytes = 0L, originalBytes = 8L))
    }
}
