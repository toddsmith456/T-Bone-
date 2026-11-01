package social.tbone.logging

import java.io.File
import java.io.PrintWriter
import java.time.Instant

/** Records the most recent uncaught Java/Kotlin exception locally, then delegates normally. */
class CrashRecorder(
    private val crashFile: File,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            crashFile.parentFile?.mkdirs()
            PrintWriter(crashFile.bufferedWriter()).use { out ->
                out.println("Uncaught exception at ${Instant.now()} on thread ${thread.name}")
                error.printStackTrace(out)
            }
        } catch (_: Throwable) {
            // Never prevent Android's normal crash handling if recording fails.
        } finally {
            previous?.uncaughtException(thread, error)
        }
    }
}
