package social.tbone.ui.lock

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinKeypadLayoutTest {
    @Test
    fun normalLayoutKeepsTheFamiliarOrderAndFixedControls() {
        val digits = PinKeypadLayout.digits(scramble = false)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"), digits)
        assertEquals(
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("←", "0", "")),
            PinKeypadLayout.rows(digits),
        )
    }

    @Test
    fun scrambledLayoutIsPermutationAndKeepsBackspaceAndBlankFixed() {
        val digits = PinKeypadLayout.digits(scramble = true, random = Random(42))
        assertEquals(PinKeypadLayout.DEFAULT_DIGITS.toSet(), digits.toSet())
        assertEquals(10, digits.distinct().size)
        assertNotEquals(PinKeypadLayout.DEFAULT_DIGITS, digits)
        assertEquals(listOf("←", digits.last(), ""), PinKeypadLayout.rows(digits).last())
    }

    @Test
    fun eachScrambledAttemptMayHaveANewLayout() {
        val first = PinKeypadLayout.digits(scramble = true, random = Random(1))
        val second = PinKeypadLayout.digits(scramble = true, random = Random(2))
        assertTrue(first != second)
    }
}
