package social.tbone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CrashRecorderTest {
    @Test fun `writes crash locally and delegates to previous handler`() {
        val dir = Files.createTempDirectory("tbone-crash-test").toFile()
        val file = File(dir, "last-crash.log")
        val historyDir = File(dir, CRASH_HISTORY_DIR)
        val error = IllegalStateException("diagnostic failure")
        var delegated: Throwable? = null
        try {
            val prior = Thread.UncaughtExceptionHandler { _, thrown -> delegated = thrown }
            CrashRecorder(file, prior, historyDir, appVersion = "0.5.9 (75)")
                .uncaughtException(Thread.currentThread(), error)
            assertSame(error, delegated)
            val content = file.readText()
            assertTrue(content.contains("IllegalStateException: diagnostic failure"))
            assertTrue(content.contains("App version: 0.5.9 (75)"))
            val historyFiles = historyDir.listFiles()?.toList().orEmpty()
            assertEquals(1, historyFiles.size)
            assertTrue(historyFiles.first().readText().contains("diagnostic failure"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `preserves multiple sequential crashes without overwriting history`() {
        val dir = Files.createTempDirectory("tbone-crash-history").toFile()
        val file = File(dir, "last-crash.log")
        val historyDir = File(dir, CRASH_HISTORY_DIR)
        try {
            val recorder = CrashRecorder(file, previous = null, historyDir = historyDir)
            recorder.uncaughtException(Thread.currentThread(), IllegalStateException("crash-one"))
            recorder.uncaughtException(Thread.currentThread(), NullPointerException("crash-two"))

            // last-crash.log has the latest crash
            assertTrue(file.readText().contains("crash-two"))

            // history preserves both crashes
            val texts = historyDir.listFiles()?.map { it.readText() }.orEmpty()
            assertEquals(2, texts.size)
            assertTrue(texts.any { it.contains("crash-one") })
            assertTrue(texts.any { it.contains("crash-two") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `prunes oldest crashes beyond history cap`() {
        val dir = Files.createTempDirectory("tbone-crash-prune").toFile()
        val file = File(dir, "last-crash.log")
        val historyDir = File(dir, CRASH_HISTORY_DIR)
        try {
            val recorder = CrashRecorder(file, previous = null, historyDir = historyDir)
            repeat(30) { idx ->
                recorder.uncaughtException(Thread.currentThread(), RuntimeException("err-$idx"))
            }
            val files = historyDir.listFiles()?.toList().orEmpty()
            assertEquals(25, files.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
