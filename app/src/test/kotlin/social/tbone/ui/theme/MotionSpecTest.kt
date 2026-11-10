package social.tbone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Phase 3 motion constants and the classic-mode opt-out. */
class MotionSpecTest {

    @Test
    fun `classic keeps navigation compose defaults`() {
        assertNull(youniversalNavMotion(false))
    }

    @Test
    fun `youniversal gets snappy slide-fade navigation`() {
        val spec = youniversalNavMotion(true)
        assertTrue("spec present", spec != null)
        // DurationFast from the Youniversal motion table (180ms).
        assertEquals(180, spec!!.durationMs)
        // A subtle slide: a fraction, never a full-screen shove.
        assertTrue("fraction in range", spec.slideFraction in 0.01f..0.5f)
    }

    @Test
    fun `engagement press scales`() {
        assertEquals(0.92f, engagementPressScale(pressed = true), 0.001f)
        assertEquals(1f, engagementPressScale(pressed = false), 0.001f)
    }

    @Test
    fun `like pop scale`() {
        assertEquals(1.15f, likePopScale(pop = true), 0.001f)
        assertEquals(1f, likePopScale(pop = false), 0.001f)
    }
}
