package social.tbone.tokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steak data is fetched only while a screen is showing it. These are the edges
 * that decide whether the live subscription is running, so getting them wrong
 * either kills live updates while a sheet is open or leaves four REQs on every
 * relay for the rest of the session — the exact cost that was moved off startup.
 */
class SurfaceRefCounterTest {

    @Test
    fun `only the first holder starts the work`() {
        val counter = SurfaceRefCounter()
        assertTrue("0 -> 1 must start", counter.acquire())
        assertFalse("a second holder must not restart", counter.acquire())
        assertEquals(2, counter.holders())
    }

    @Test
    fun `work stops only when the last holder leaves`() {
        val counter = SurfaceRefCounter()
        counter.acquire()
        counter.acquire()

        assertFalse("one of two closing is not enough", counter.release())
        assertTrue(counter.isHeld())
        assertTrue("the last holder must stop it", counter.release())
        assertFalse(counter.isHeld())
    }

    @Test
    fun `a double release cannot strand the subscription or go negative`() {
        val counter = SurfaceRefCounter()
        counter.acquire()
        assertTrue(counter.release())
        // A second onDispose for the same surface (recomposition, a cancelled
        // sheet) must be a no-op rather than reporting a second 1 -> 0 edge.
        assertFalse(counter.release())
        assertEquals(0, counter.holders())
    }

    @Test
    fun `an open surface survives an account switch`() {
        val counter = SurfaceRefCounter()
        counter.acquire()
        // Switching account does not dispose the sheet; isHeld() is what the
        // repository consults to re-subscribe against the new pubkey.
        assertTrue(counter.isHeld())
        counter.reset()
        assertFalse("a wipe must drop everything", counter.isHeld())
    }

    @Test
    fun `interleaved open and close sequences stay balanced`() {
        val counter = SurfaceRefCounter()
        val starts = mutableListOf<Boolean>()
        val stops = mutableListOf<Boolean>()
        repeat(3) { starts += counter.acquire() }
        repeat(2) { stops += counter.release() }
        starts += counter.acquire()
        repeat(3) { stops += counter.release() }

        assertEquals("exactly one start edge", 1, starts.count { it })
        assertEquals("exactly one stop edge", 1, stops.count { it })
        assertEquals(0, counter.holders())
    }
}
