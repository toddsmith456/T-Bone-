package social.tbone.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoundedCopyTest {
    @Test fun exactLimitSucceedsAndOversizeIsRejected() = runTest {
        val output = ByteArrayOutputStream()
        assertEquals(3L, ByteArrayInputStream(byteArrayOf(1, 2, 3)).copyBounded(output, 3, "too large"))
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        try { ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)).copyBounded(ByteArrayOutputStream(), 3, "too large"); fail() }
        catch (e: IllegalArgumentException) { assertEquals("too large", e.message) }
    }
    @Test fun stalledStreamFailsInsteadOfSpinning() = runTest {
        val stalled = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = 0
        }
        try { stalled.copyBounded(ByteArrayOutputStream(), 10, "too large"); fail() } catch (_: IOException) { }
    }
    @Test fun alreadyCancelledCopyDoesNotReadSource() = runTest {
        var reads = 0
        val input = object : java.io.InputStream() { override fun read(): Int { reads++; return -1 } }
        val job = launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try { input.copyBounded(ByteArrayOutputStream(), 10, "large") } catch (_: CancellationException) { }
        }
        job.cancelAndJoin()
        assertEquals(0, reads)
    }
}
