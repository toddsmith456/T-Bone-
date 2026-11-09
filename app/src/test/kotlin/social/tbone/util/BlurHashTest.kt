package social.tbone.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BlurHashTest {

    @Test
    fun `invalid hashes are rejected`() {
        assertNull(BlurHash.decode(null, 8, 8))
        assertNull(BlurHash.decode("nope", 8, 8))
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 0, 8))
        assertTrue(!BlurHash.isValid("LEHV6nWB2yk8pyo0adR*.7kCMdnj".dropLast(2) + "!!"))
    }

    @Test
    fun `a known hash decodes to a non-blank image`() {
        val decoded = BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 16, 12)
        assertNotNull(decoded)
        assertEquals(16 * 12, decoded!!.pixels.size)
        // Not a flat colour: the example hash has visible variation.
        assertTrue(decoded.pixels.distinct().size > 4)
        assertTrue(decoded.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test
    fun `encode then decode preserves a solid colour`() {
        val argb = (0xFF shl 24) or (200 shl 16) or (60 shl 8) or 40
        val pixels = IntArray(12 * 9) { argb }
        val hash = BlurHash.encode(pixels, 12, 9)
        assertTrue(BlurHash.isValid(hash))
        val decoded = BlurHash.decode(hash, 12, 9)!!
        val p = decoded.pixels[0]
        assertTrue(abs(((p shr 16) and 255) - 200) <= 4)
        assertTrue(abs(((p shr 8) and 255) - 60) <= 4)
        assertTrue(abs((p and 255) - 40) <= 4)
        // Solid input stays (near-)solid.
        assertTrue(decoded.pixels.distinct().size <= 3)
    }

    @Test
    fun `a two-tone image keeps its average colour`() {
        val w = 16
        val h = 8
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(w * h) { i -> if ((i % w) < w / 2) black else white }
        val hash = BlurHash.encode(pixels, w, h)
        val decoded = BlurHash.decode(hash, w, h)!!
        val left = decoded.pixels[2 * w + 2]
        val right = decoded.pixels[2 * w + (w - 3)]
        val leftLum = ((left shr 16) and 255) + ((left shr 8) and 255) + (left and 255)
        val rightLum = ((right shr 16) and 255) + ((right shr 8) and 255) + (right and 255)
        assertTrue("left should be darker than right", leftLum < rightLum)
    }
}
