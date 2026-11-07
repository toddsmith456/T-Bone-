package social.tbone.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BoundedInputTest {
    @Test fun acceptsExactLimit() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBounded(2))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedInput() { byteArrayOf(1, 2, 3).inputStream().readBounded(2) }
}
