package social.tbone.logging

import android.util.Log
import social.tbone.BuildConfig
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val MAX_LOG_BYTES = 2 * 1024 * 1024L // 2 MB
private val TIMESTAMP_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/**
 * Timber tree that writes log lines to [logFile] in the app's private files dir.
 *
 * Rotates: when the file exceeds [MAX_LOG_BYTES] it is moved to [logFile].1
 * and a fresh file is started, giving two generations of history.
 *
 * Thread-safe: all writes and the rotate check go through a single lock.
 * In release builds only INFO and above are written (no VERBOSE/DEBUG).
 */
class FileLoggingTree(private val logFile: File) : Timber.Tree() {

    private val lock = Any()
    private var writer: BufferedWriter

    init {
        logFile.parentFile?.mkdirs()
        writer = openWriter()
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!BuildConfig.DEBUG && priority < Log.INFO) return
        try {
            val level = priorityLabel(priority)
            val timestamp = TIMESTAMP_FMT.format(LocalDateTime.now())
            val line = buildString {
                append("$timestamp $level/$tag: $message")
                if (t != null) append("\n${Log.getStackTraceString(t)}")
                append("\n")
            }
            synchronized(lock) {
                rotateIfNeeded()
                writer.write(line)
                writer.flush()
            }
        } catch (_: Exception) {
            // Never crash because of logging
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.length() < MAX_LOG_BYTES) return
        writer.close()
        val rotated = File(logFile.parent, "${logFile.name}.1")
        rotated.delete()
        logFile.renameTo(rotated)
        writer = openWriter()
    }

    private fun openWriter(): BufferedWriter = BufferedWriter(FileWriter(logFile, /* append = */ true))

    private fun priorityLabel(priority: Int) = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG   -> "D"
        Log.INFO    -> "I"
        Log.WARN    -> "W"
        Log.ERROR   -> "E"
        Log.ASSERT  -> "A"
        else        -> "?"
    }
}
