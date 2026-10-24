package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry behind the playable video share card.
 *
 * The card is drawn at 1080 px wide and can be up to 8000 px tall; the MP4 has
 * to be an even-sized frame no longer than 3840 px, with the live video window
 * mapped into exactly the same place.
 */
class ShareCardVideoLayoutTest {

    @Test
    fun ordinaryCardsKeepTheirPixelSize() {
        assertEquals(1080 to 2400, ShareCardVideoLayout.outputSize(1080, 2400))
        assertEquals(1080 to 640, ShareCardVideoLayout.outputSize(1080, 640))
    }

    @Test
    fun veryTallCardsAreScaledDownToTheEncoderCeiling() {
        val (width, height) = ShareCardVideoLayout.outputSize(1080, 8000)
        assertEquals(3840, height)
        assertTrue("width was $width", width in 500..520)
        assertEquals(0, width % 2)
    }

    @Test
    fun theVideoWindowKeepsItsPlaceAndAspect() {
        val frame = ShareCardVideoLayout.slotInOutput(
            left = 100f,
            top = 600f,
            right = 700f,
            bottom = 1000f,
            cardWidth = 2160,
            cardHeight = 4800,
            outputWidth = 1080,
            outputHeight = 2400,
        )
        assertEquals(50f, frame.left, 0.01f)
        assertEquals(300f, frame.top, 0.01f)
        assertEquals(300, frame.width)
        assertEquals(200, frame.height)
        assertEquals(1.5f, frame.width.toFloat() / frame.height, 0.001f)
    }

    @Test
    fun windowsAreClampedInsideTheFrameAndEvenSized() {
        val frame = ShareCardVideoLayout.slotInOutput(
            left = -40f,
            top = 100f,
            right = 5000f,
            bottom = 5000f,
            cardWidth = 1080,
            cardHeight = 1080,
            outputWidth = 1080,
            outputHeight = 1080,
        )
        assertTrue(frame.left >= 0f)
        assertTrue(frame.top >= 0f)
        assertTrue(frame.right <= 1080f)
        assertTrue(frame.bottom <= 1080f)
        assertEquals(0, frame.width % 2)
        assertEquals(0, frame.height % 2)
    }

    /**
     * Media3's compositor already scales the overlay by overlay/background, so
     * any scale other than 1 double-scales the video window (the old bug left a
     * postage-stamp video in the middle of an otherwise correct slot).
     */
    @Test
    fun theCompositorMustNotScaleTheAlreadySizedWindow() {
        assertEquals(1f, ShareCardVideoLayout.OVERLAY_SCALE, 0f)
    }
}
