package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GreentextTest {

    @Test
    fun `a plain line is not greentext`() {
        assertTrue(greentextLineRanges("hello world").isEmpty())
        assertTrue(greentextLineRanges("a > b comparison").isEmpty())
    }

    @Test
    fun `a line starting with greater-than is greentext`() {
        val text = ">be me"
        assertEquals(listOf(0 until 6), greentextLineRanges(text))
    }

    @Test
    fun `only the greentext lines are picked out`() {
        val text = "first line\n>mfw no gf\nlast > line"
        assertEquals(listOf(11 until 21), greentextLineRanges(text))
    }

    @Test
    fun `leading spaces still count and empty text is safe`() {
        val text = "  >indented quote"
        val ranges = greentextLineRanges(text)
        assertEquals(1, ranges.size)
        assertEquals(0, ranges.single().first)
        assertTrue(greentextLineRanges("").isEmpty())
        assertTrue(greentextLineRanges(">").isEmpty().not())
    }
}
