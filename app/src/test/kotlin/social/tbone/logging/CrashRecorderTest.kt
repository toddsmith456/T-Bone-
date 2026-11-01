package social.tbone.logging

import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

class CrashRecorderTest {
    @Test fun `writes crash locally and delegates to previous handler`() {
        val file = File.createTempFile("tbone-crash", ".log")
        val error = IllegalStateException("diagnostic failure")
        var delegated: Throwable? = null
        try {
            val prior = Thread.UncaughtExceptionHandler { _, thrown -> delegated = thrown }
            CrashRecorder(file, prior).uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            assertTrue(file.readText().contains("IllegalStateException: diagnostic failure"))
        } finally { file.delete() }
    }
}
