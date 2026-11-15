package social.tbone.logging

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

const val CRASH_HISTORY_DIR = "crashes"
private const val MAX_STORED_CRASHES = 25

/**
 * Records uncaught Java/Kotlin exceptions locally — both to [crashFile] (latest
 * crash for backwards compatibility) and to a bounded history under [historyDir]
 * so earlier crashes are not overwritten — then delegates normally.
 */
class CrashRecorder(
    private val crashFile: File,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val historyDir: File? = crashFile.parentFile?.let { File(it, CRASH_HISTORY_DIR) },
    private val appVersion: String = "",
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val now = Instant.now()
            val payload = formatCrash(now, thread, error)

            crashFile.parentFile?.mkdirs()
            crashFile.writeText(payload)

            historyDir?.let { dir ->
                dir.mkdirs()
                val seq = sequenceCounter.incrementAndGet()
                val entryFile = File(dir, "crash-${now.toEpochMilli()}-$seq.log")
                entryFile.writeText(payload)
                pruneHistory(dir)
            }
        } catch (_: Throwable) {
            // Never prevent Android's normal crash handling if recording fails.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }

    private fun formatCrash(now: Instant, thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> error.printStackTrace(pw) }
        }.toString()
        return buildString {
            appendLine("Uncaught exception at $now on thread ${thread.name}")
            if (appVersion.isNotBlank()) {
                appendLine("App version: $appVersion")
            }
            append(trace)
        }
    }

    private fun pruneHistory(dir: File) {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: return
        if (files.size <= MAX_STORED_CRASHES) return
        files.drop(MAX_STORED_CRASHES).forEach { runCatching { it.delete() } }
    }

    private companion object {
        val sequenceCounter = AtomicInteger(0)
    }
}
