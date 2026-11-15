package social.tbone.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import social.tbone.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val LOG_DIR = "logs"
const val LOG_FILE_NAME = "tbone.log"
private const val MAX_TRACE_READ_BYTES = 4_096
private const val MAX_EXIT_REASONS = 25

@Singleton
class LogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logsDir: File = File(context.filesDir, LOG_DIR)
    val logFile: File = File(logsDir, LOG_FILE_NAME)
    private val rotatedLogFile: File = File(logsDir, "$LOG_FILE_NAME.1")
    private val lastCrashFile: File = File(logsDir, "last-crash.log")
    private val crashHistoryDir: File = File(logsDir, CRASH_HISTORY_DIR)

    /** Returns counts for the dialog preview without generating the full report text. */
    fun previewCounts(options: LogExportOptions): LogPreviewCounts = LogFormatter.summarize(
        nowMillis = System.currentTimeMillis(),
        options = options,
        crashes = loadCrashes(),
        systemExits = loadSystemExits(),
        logRecords = loadLogRecords(),
    )

    /** Builds a clean, time-filtered, bounded diagnostic report suitable for clipboard or file share. */
    fun buildLogText(options: LogExportOptions): String = LogFormatter.formatReport(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        nowMillis = System.currentTimeMillis(),
        options = options,
        crashes = loadCrashes(),
        systemExits = loadSystemExits(),
        logRecords = loadLogRecords(),
    )

    /** Returns an Intent that shares the cleaned, filtered log file via any app the user picks. */
    fun buildShareIntent(options: LogExportOptions = LogExportOptions()): Intent {
        val shareOptions = options.copy(maxChars = LogFormatter.DEFAULT_SHARE_MAX_CHARS)
        val reportText = buildLogText(shareOptions)
        val fileName = if (shareOptions.crashesOnly) "tbone-crashes.txt" else "tbone-logs.txt"
        val sharedFile = runCatching {
            val target = File(logsDir, "share/$fileName")
            target.parentFile?.mkdirs()
            target.writeText(reportText, Charsets.UTF_8)
            target
        }.getOrDefault(logFile)

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sharedFile,
        )
        val subjectKind = if (shareOptions.crashesOnly) "crashes" else "logs"
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "T-bone $subjectKind (${shareOptions.range.label})")
            putExtra(
                Intent.EXTRA_TEXT,
                "⚠️ May contain crash details, relay URLs, and event metadata. Share only with trusted parties.",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun loadCrashes(): List<CrashRecord> {
        val records = mutableListOf<CrashRecord>()
        if (crashHistoryDir.isDirectory) {
            crashHistoryDir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
                ?.forEach { file ->
                    readCrashFile(file)?.let { records += it }
                }
        }
        if (lastCrashFile.isFile) {
            readCrashFile(lastCrashFile)?.let { records += it }
        }
        return records
    }

    private fun readCrashFile(file: File): CrashRecord? = runCatching {
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return@runCatching null
        val ts = LogFormatter.parseCrashTimestamp(text, file.lastModified())
        CrashRecord(timestampMillis = ts, text = text)
    }.getOrNull()

    private fun loadLogRecords(): List<LogRecord> {
        val records = ArrayList<LogRecord>()
        for (file in listOf(rotatedLogFile, logFile)) {
            if (!file.isFile) continue
            runCatching {
                val text = file.readText(Charsets.UTF_8)
                records.addAll(LogFormatter.parseLogText(text))
            }
        }
        return records
    }

    private fun loadSystemExits(): List<SystemExitRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching { loadSystemExitsApi30() }.getOrDefault(emptyList())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun loadSystemExitsApi30(): List<SystemExitRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_REASONS)
        return reasons.mapNotNull { info ->
            val label = abnormalExitReasonLabel(info.reason) ?: return@mapNotNull null
            SystemExitRecord(
                timestampMillis = info.timestamp,
                reason = label,
                importance = importanceLabel(info.importance),
                status = info.status,
                description = info.description,
                pssKb = info.pss,
                rssKb = info.rss,
                traceExcerpt = readTraceExcerpt(info),
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun abnormalExitReasonLabel(reason: Int): String? = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (C/C++ signal)"
        ApplicationExitInfo.REASON_ANR -> "ANR (main thread freeze)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (OOM / LMK kill)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (OS kill signal)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        else -> null
    }

    private fun importanceLabel(importance: Int): String = when (importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foreground_service"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
        else -> "code=$importance"
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTraceExcerpt(info: ApplicationExitInfo): String? = runCatching {
        val stream = info.traceInputStream ?: return@runCatching null
        stream.use { input ->
            val buf = ByteArray(MAX_TRACE_READ_BYTES)
            var offset = 0
            while (offset < buf.size) {
                val read = input.read(buf, offset, buf.size - offset)
                if (read <= 0) break
                offset += read
            }
            if (offset == 0) return@use null
            val raw = String(buf, 0, offset, Charsets.UTF_8)
            // Strip any non-printable binary bytes while keeping newlines and tabs.
            raw.filter { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }
                .trim()
                .ifEmpty { null }
        }
    }.getOrNull()
}
