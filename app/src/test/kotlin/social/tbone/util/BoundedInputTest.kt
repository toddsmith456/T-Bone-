package social.tbone.util

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class BoundedInputTest {
    @Test fun acceptsExactLimit() {
        assertArrayEquals(byteArrayOf(1, 2), byteArrayOf(1, 2).inputStream().readBounded(2))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedInput() { byteArrayOf(1, 2, 3).inputStream().readBounded(2) }

    @Test(expected = java.io.IOException::class)
    fun rejectsStreamThatNeverMakesProgress() {
        val stream = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = 0
        }
        stream.readBounded(10)
    }

    @Test fun permitsEmptyInputAtZeroLimit() {
        assertArrayEquals(byteArrayOf(), byteArrayOf().inputStream().readBounded(0))
    }
}
