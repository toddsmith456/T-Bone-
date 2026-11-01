package social.tbone.logging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_DIR = "logs"
const val LOG_FILE_NAME = "tbone.log"

@Singleton
class LogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val logFile: File = File(context.filesDir, "$LOG_DIR/$LOG_FILE_NAME")

    /** Returns an Intent that shares the current log file via any app the user picks. */
    fun buildShareIntent(): Intent {
        // Include the most recent crash separately from the rotating network log,
        // so a relay-error flood or app restart cannot hide the stack trace.
        val crash = File(context.filesDir, "$LOG_DIR/last-crash.log")
        val sharedLog = if (crash.isFile) {
            runCatching {
                val target = File(context.filesDir, "$LOG_DIR/share/$LOG_FILE_NAME")
                target.parentFile?.mkdirs()
                target.outputStream().buffered().use { out ->
                    out.write("=== LAST UNCAUGHT CRASH ===\n".toByteArray())
                    crash.inputStream().use { it.copyTo(out) }
                    out.write("\n=== RECENT APP LOG ===\n".toByteArray())
                    if (logFile.isFile) logFile.inputStream().use { it.copyTo(out) }
                }
                target
            }.getOrDefault(logFile)
        } else logFile
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sharedLog,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "T-bone debug log")
            putExtra(
                Intent.EXTRA_TEXT,
                "⚠️ These logs may contain crash details, relay URLs, and event metadata. Share only with trusted parties.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
