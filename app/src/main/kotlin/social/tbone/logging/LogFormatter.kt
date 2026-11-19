package social.tbone.logging

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How far back to pull crashes and logs when exporting or copying. */
enum class LogTimeRange(
    val label: String,
    val durationMillis: Long?,
) {
    LAST_DAY("last day", 24L * 60 * 60 * 1000),
    LAST_WEEK("last week", 7L * 24 * 60 * 60 * 1000),
    LAST_MONTH("last month", 30L * 24 * 60 * 60 * 1000),
    ALL_TIME("all time", null),
}

/** User-chosen options for copying or sharing diagnostic logs. */
data class LogExportOptions(
    val range: LogTimeRange = LogTimeRange.LAST_DAY,
    val crashesOnly: Boolean = false,
    val maxChars: Int = LogFormatter.DEFAULT_SHARE_MAX_CHARS,
    /**
     * Include the performance section.
     *
     * Kept separate from [crashesOnly] on purpose: crash logs are crash logs, and
     * performance numbers answer a different question ("why was it slow?") with a
     * different kind of data (timings and counts, no message content, no relay
     * URLs, no pubkeys). A report can carry either, both or neither.
     */
    val includePerformance: Boolean = true,
)

/** One recorded uncaught Java/Kotlin crash. */
data class CrashRecord(
    val timestampMillis: Long,
    val text: String,
)

/** One abnormal Android process exit (native crash, OOM/LMK kill, ANR, etc.). */
data class SystemExitRecord(
    val timestampMillis: Long,
    val reason: String,
    val importance: String,
    val status: Int,
    val description: String?,
    val pssKb: Long,
    val rssKb: Long,
    val traceExcerpt: String? = null,
)

/** One parsed Timber log record (header line plus optional stack-trace continuation lines). */
data class LogRecord(
    val timestampMillis: Long,
    val rawTimestamp: String,
    val level: Char,
    val tag: String,
    val message: String,
    val continuationLines: List<String> = emptyList(),
)

/** Collapsed consecutive identical log records for concise, readable output. */
data class CollapsedLogRecord(
    val firstTimestamp: String,
    val lastTimestamp: String,
    val level: Char,
    val tag: String,
    val message: String,
    val continuationLines: List<String>,
    val count: Int,
)

/** Lightweight counts shown inside the Share Logs dialog before copying or sharing. */
data class LogPreviewCounts(
    val uncaughtCrashes: Int,
    val systemExits: Int,
    val errors: Int,
    val logEntries: Int,
) {
    val totalCrashSignals: Int get() = uncaughtCrashes + systemExits + errors

    fun summaryLine(crashesOnly: Boolean): String {
        val parts = buildList {
            if (uncaughtCrashes > 0) {
                add("$uncaughtCrashes ${if (uncaughtCrashes == 1) "crash" else "crashes"}")
            }
            if (systemExits > 0) {
                add("$systemExits ${if (systemExits == 1) "system kill" else "system kills"}")
            }
            if (errors > 0) {
                add("$errors ${if (errors == 1) "error" else "errors"}")
            }
            if (!crashesOnly && logEntries > 0) {
                add("$logEntries log ${if (logEntries == 1) "entry" else "entries"}")
            }
        }
        return when {
            parts.isNotEmpty() -> parts.joinToString(" · ")
            crashesOnly -> "no crashes or errors recorded in this range"
            else -> "no logs recorded in this range"
        }
    }
}

/**
 * Pure-Kotlin formatter that parses, time-filters, deduplicates, compacts, and
 * bounds T-Bone crash & activity logs so exports stay clean and easy to read.
 */
object LogFormatter {
    const val DEFAULT_CLIPBOARD_MAX_CHARS = 40_000
    const val DEFAULT_SHARE_MAX_CHARS = 120_000
    private const val MAX_STACK_FRAMES_PER_SECTION = 30
    private const val MAX_LOG_CONTINUATION_LINES = 16
    private const val MAX_SYSTEM_TRACE_LINES = 45
    private const val MAX_MESSAGE_LINE_CHARS = 500

    private val LOG_TIMESTAMP_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val DISPLAY_UTC_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US)
    private val LOG_HEADER_REGEX =
        Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEA?])/([^:]*): ?(.*)$""")
    private val CRASH_ISO_REGEX =
        Regex("""(?:Uncaught exception at |Timestamp:\s*)(\d{4}-\d{2}-\d{2}T[^\s]+)""")

    /** Extracts the UTC epoch millis embedded in a crash file, falling back to [fallbackMillis]. */
    fun parseCrashTimestamp(text: String, fallbackMillis: Long): Long {
        val match = CRASH_ISO_REGEX.find(text) ?: return fallbackMillis
        return runCatching { Instant.parse(match.groupValues[1]).toEpochMilli() }
            .getOrDefault(fallbackMillis)
    }

    /** Parses Timber `FileLoggingTree` output into structured multi-line [LogRecord]s. */
    fun parseLogText(
        text: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): List<LogRecord> {
        if (text.isBlank()) return emptyList()
        val result = ArrayList<LogRecord>()
        var currentTsMillis = 0L
        var currentRawTs = ""
        var currentLevel = 'I'
        var currentTag = ""
        var currentMsg = ""
        var continuations: MutableList<String>? = null

        fun flush() {
            val cont = continuations ?: return
            result += LogRecord(
                timestampMillis = currentTsMillis,
                rawTimestamp = currentRawTs,
                level = currentLevel,
                tag = currentTag,
                message = currentMsg,
                continuationLines = cont.toList(),
            )
            continuations = null
        }

        for (rawLine in text.lineSequence()) {
            val match = LOG_HEADER_REGEX.matchEntire(rawLine)
            if (match != null) {
                flush()
                val tsText = match.groupValues[1]
                val tsMillis = runCatching {
                    LocalDateTime.parse(tsText, LOG_TIMESTAMP_FMT)
                        .atZone(zoneId)
                        .toInstant()
                        .toEpochMilli()
                }.getOrDefault(0L)
                currentTsMillis = tsMillis
                currentRawTs = tsText
                currentLevel = match.groupValues[2].firstOrNull() ?: '?'
                currentTag = match.groupValues[3].trim().ifEmpty { "App" }
                currentMsg = match.groupValues[4].trimEnd()
                continuations = mutableListOf()
            } else if (continuations != null && rawLine.isNotBlank()) {
                continuations!!.add(rawLine.trimEnd())
            }
        }
        flush()
        return result
    }

    /**
     * Trims excessively long framework/coroutine stack tails while preserving
     * exception headers, `Caused by:`/`Suppressed:` chains, and all `social.tbone.` frames.
     */
    fun compactStackTrace(
        text: String,
        maxFramesPerSection: Int = MAX_STACK_FRAMES_PER_SECTION,
    ): String {
        val lines = text.trimEnd().lines()
        if (lines.isEmpty()) return ""
        val out = ArrayList<String>(lines.size.coerceAtMost(80))
        var framesInSection = 0
        var omittedFrames = 0

        fun flushOmitted() {
            if (omittedFrames > 0) {
                out += "\t... ($omittedFrames framework frames omitted)"
                omittedFrames = 0
            }
        }

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()
            val isFrame = trimmed.startsWith("at ")
            val isSectionBreak = trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")

            when {
                isSectionBreak -> {
                    flushOmitted()
                    framesInSection = 0
                    out += line
                }
                isFrame -> {
                    val isAppFrame = trimmed.contains("social.tbone.") || trimmed.contains("dev.youniversal.")
                    if (framesInSection < maxFramesPerSection || isAppFrame) {
                        if (isAppFrame && omittedFrames > 0) {
                            flushOmitted()
                        }
                        out += line
                        framesInSection++
                    } else {
                        omittedFrames++
                    }
                }
                else -> {
                    flushOmitted()
                    out += line
                }
            }
        }
        flushOmitted()
        return out.joinToString("\n")
    }

    /** Collapses consecutive identical log entries into a single line with a repeat count. */
    fun collapseConsecutive(records: List<LogRecord>): List<CollapsedLogRecord> {
        if (records.isEmpty()) return emptyList()
        val out = ArrayList<CollapsedLogRecord>(records.size)
        var current = CollapsedLogRecord(
            firstTimestamp = records[0].rawTimestamp,
            lastTimestamp = records[0].rawTimestamp,
            level = records[0].level,
            tag = records[0].tag,
            message = records[0].message,
            continuationLines = records[0].continuationLines,
            count = 1,
        )
        for (i in 1 until records.size) {
            val next = records[i]
            val same = next.level == current.level &&
                next.tag == current.tag &&
                next.message == current.message &&
                next.continuationLines == current.continuationLines
            if (same) {
                current = current.copy(
                    lastTimestamp = next.rawTimestamp,
                    count = current.count + 1,
                )
            } else {
                out += current
                current = CollapsedLogRecord(
                    firstTimestamp = next.rawTimestamp,
                    lastTimestamp = next.rawTimestamp,
                    level = next.level,
                    tag = next.tag,
                    message = next.message,
                    continuationLines = next.continuationLines,
                    count = 1,
                )
            }
        }
        out += current
        return out
    }

    /** Computes lightweight filtered counts for the dialog preview. */
    fun summarize(
        nowMillis: Long,
        options: LogExportOptions,
        crashes: List<CrashRecord>,
        systemExits: List<SystemExitRecord>,
        logRecords: List<LogRecord>,
    ): LogPreviewCounts {
        val cutoff = options.range.durationMillis?.let { nowMillis - it }
        val filteredCrashes = deduplicateCrashes(crashes).filter { matchesCutoff(it.timestampMillis, cutoff) }
        val filteredExits = systemExits.filter { matchesCutoff(it.timestampMillis, cutoff) }
        val filteredLogs = logRecords.filter { matchesCutoff(it.timestampMillis, cutoff) }
        val errorCount = filteredLogs.count { it.level == 'E' || it.level == 'A' }
        return LogPreviewCounts(
            uncaughtCrashes = filteredCrashes.size,
            systemExits = filteredExits.size,
            errors = errorCount,
            logEntries = if (options.crashesOnly) errorCount else filteredLogs.size,
        )
    }

    /** Formats a clean, readable, size-bounded diagnostic report. */
    fun formatReport(
        appVersion: String,
        androidRelease: String,
        sdkInt: Int,
        nowMillis: Long,
        options: LogExportOptions,
        crashes: List<CrashRecord>,
        systemExits: List<SystemExitRecord>,
        logRecords: List<LogRecord>,
        zoneId: ZoneId = ZoneId.systemDefault(),
        performanceSection: String? = null,
    ): String {
        val cutoff = options.range.durationMillis?.let { nowMillis - it }
        val maxChars = options.maxChars.coerceAtLeast(1_024)

        val filteredCrashes = deduplicateCrashes(crashes)
            .filter { matchesCutoff(it.timestampMillis, cutoff) }
            .sortedByDescending { it.timestampMillis }

        val filteredExits = systemExits
            .filter { matchesCutoff(it.timestampMillis, cutoff) }
            .sortedByDescending { it.timestampMillis }

        val filteredLogs = logRecords
            .filter { matchesCutoff(it.timestampMillis, cutoff) }
            .sortedBy { it.timestampMillis }

        val errorLogs = filteredLogs.filter { it.level == 'E' || it.level == 'A' }
        val scopeLabel = if (options.crashesOnly) "Crashes only" else "All logs"

        val header = buildString {
            appendLine("T-Bone Diagnostics Report")
            appendLine("Version: $appVersion · Android $androidRelease (API $sdkInt)")
            appendLine("Range: ${options.range.label} · Scope: $scopeLabel")
            appendLine("Generated: ${formatUtc(nowMillis)}")
            appendLine(
                "Summary: ${filteredCrashes.size} uncaught crash(es), " +
                    "${filteredExits.size} system kill(s), ${errorLogs.size} logged error(s)" +
                    if (options.crashesOnly) "" else ", ${filteredLogs.size} total log line(s)",
            )
        }

        val sections = StringBuilder()
        sections.append(header)

        // 0. Performance block first when requested: it is the shortest path to
        // "was this a cold-start problem or a crash?" for whoever reads the share.
        if (options.includePerformance && !performanceSection.isNullOrBlank()) {
            sections.appendLine()
            sections.append(performanceSection)
        }

        // 1. Uncaught Java/Kotlin crashes (always highest priority, newest first).
        if (filteredCrashes.isNotEmpty()) {
            sections.appendLine()
            sections.appendLine("=== UNCAUGHT CRASHES (${filteredCrashes.size}, newest first) ===")
            filteredCrashes.forEachIndexed { index, crash ->
                if (index > 0) sections.appendLine()
                sections.appendLine("--- Crash #${index + 1} (${formatUtc(crash.timestampMillis)}) ---")
                sections.appendLine(compactStackTrace(crash.text))
            }
        }

        // 2. Android OS process exit reasons (native crashes, OOM/LMK kills, ANRs).
        if (filteredExits.isNotEmpty()) {
            sections.appendLine()
            sections.appendLine("=== SYSTEM CRASH / KILL EVENTS (${filteredExits.size}, newest first) ===")
            filteredExits.forEachIndexed { index, exit ->
                if (index > 0) sections.appendLine()
                sections.appendLine(formatSystemExit(index + 1, exit))
            }
        }

        // 3. Either logged errors (crashesOnly) or clean collapsed app logs (all logs).
        val targetRecords = if (options.crashesOnly) errorLogs else filteredLogs
        val collapsed = collapseConsecutive(targetRecords)
        val remainingBudget = maxChars - sections.length

        if (collapsed.isNotEmpty() && remainingBudget > 256) {
            val title = if (options.crashesOnly) {
                "=== LOGGED ERRORS (${errorLogs.size} entries, ${collapsed.size} unique) ==="
            } else {
                "=== APP LOG (${filteredLogs.size} entries, ${collapsed.size} collapsed) ==="
            }
            sections.appendLine()
            sections.appendLine(title)
            appendTailBoundedEntries(
                out = sections,
                entries = collapsed,
                budgetChars = (maxChars - sections.length).coerceAtLeast(0),
            )
        } else if (filteredCrashes.isEmpty() && filteredExits.isEmpty() && collapsed.isEmpty()) {
            sections.appendLine()
            val emptyMsg = if (options.crashesOnly) {
                "No crashes, system kills, or errors recorded in this time range."
            } else {
                "No logs recorded in this time range."
            }
            sections.appendLine(emptyMsg)
        }

        val raw = sections.toString().trimEnd()
        return if (raw.length <= maxChars) {
            raw
        } else {
            val suffix = "\n... (report trimmed to ${maxChars / 1000} KB for readability)"
            raw.take((maxChars - suffix.length).coerceAtLeast(0)).trimEnd() + suffix
        }
    }

    private fun appendTailBoundedEntries(
        out: StringBuilder,
        entries: List<CollapsedLogRecord>,
        budgetChars: Int,
    ) {
        if (budgetChars <= 0 || entries.isEmpty()) return
        val formatted = entries.map(::formatCollapsedRecord)
        val totalChars = formatted.sumOf { it.length + 1 }
        if (totalChars <= budgetChars) {
            formatted.forEach { out.appendLine(it) }
            return
        }

        // Keep the newest entries that fit within budget so recent events around a crash stay intact.
        val noteReserve = 96
        val kept = ArrayDeque<String>()
        var used = 0
        for (i in formatted.lastIndex downTo 0) {
            val item = formatted[i]
            val cost = item.length + 1
            if (used + cost + noteReserve > budgetChars && kept.isNotEmpty()) break
            kept.addFirst(item)
            used += cost
        }
        val omitted = formatted.size - kept.size
        if (omitted > 0) {
            out.appendLine("... (omitted $omitted older log group(s) to keep report clean; showing latest ${kept.size})")
        }
        for (line in kept) {
            out.appendLine(line)
        }
    }

    private fun formatCollapsedRecord(record: CollapsedLogRecord): String {
        val ts = compactTimestamp(record.firstTimestamp)
        val msg = record.message.let {
            if (it.length > MAX_MESSAGE_LINE_CHARS) it.take(MAX_MESSAGE_LINE_CHARS) + "…" else it
        }
        val repeatSuffix = if (record.count > 1) {
            "  (x${record.count}, last ${compactTimestamp(record.lastTimestamp)})"
        } else {
            ""
        }
        if (record.continuationLines.isEmpty()) {
            return "$ts ${record.level}/${record.tag}: $msg$repeatSuffix"
        }
        val compactCont = compactStackTrace(
            record.continuationLines.joinToString("\n"),
            maxFramesPerSection = MAX_LOG_CONTINUATION_LINES,
        )
        return buildString {
            append("$ts ${record.level}/${record.tag}: $msg$repeatSuffix")
            compactCont.lineSequence().forEach { contLine ->
                append("\n  ")
                append(contLine.trimStart())
            }
        }
    }

    private fun formatSystemExit(index: Int, exit: SystemExitRecord): String = buildString {
        append("--- System Exit #$index (${formatUtc(exit.timestampMillis)}) ---")
        append("\nReason: ${exit.reason} · Status: ${exit.status} · Importance: ${exit.importance}")
        if (exit.pssKb > 0 || exit.rssKb > 0) {
            val pssMb = exit.pssKb / 1024
            val rssMb = exit.rssKb / 1024
            append("\nMemory: PSS ${pssMb} MB · RSS ${rssMb} MB")
        }
        if (!exit.description.isNullOrBlank()) {
            append("\nDescription: ${exit.description.trim()}")
        }
        if (!exit.traceExcerpt.isNullOrBlank()) {
            val traceLines = exit.traceExcerpt.trimEnd().lines()
            val clipped = if (traceLines.size > MAX_SYSTEM_TRACE_LINES) {
                traceLines.take(MAX_SYSTEM_TRACE_LINES) + "... (${traceLines.size - MAX_SYSTEM_TRACE_LINES} trace lines omitted)"
            } else {
                traceLines
            }
            append("\nTrace excerpt:\n")
            append(clipped.joinToString("\n"))
        }
    }

    /** Deduplicates crash files (e.g. `last-crash.log` duplicating the newest `crashes/` entry). */
    private fun deduplicateCrashes(crashes: List<CrashRecord>): List<CrashRecord> {
        if (crashes.size <= 1) return crashes
        val seen = LinkedHashSet<String>()
        val result = ArrayList<CrashRecord>(crashes.size)
        for (crash in crashes.sortedByDescending { it.timestampMillis }) {
            val normalized = crash.text.trim()
            if (normalized.isEmpty()) continue
            val key = "${crash.timestampMillis / 1_000}|${normalized.take(200)}"
            if (seen.add(key)) {
                result += crash
            }
        }
        return result
    }

    private fun matchesCutoff(timestampMillis: Long, cutoffMillis: Long?): Boolean {
        if (cutoffMillis == null) return true
        // Include entries with unknown/0 timestamp only for ALL_TIME so old unparseable lines don't pollute short ranges.
        return timestampMillis >= cutoffMillis
    }

    private fun compactTimestamp(raw: String): String {
        // "2026-11-14 15:30:12.345" -> "11-14 15:30:12"
        return if (raw.length >= 19) raw.substring(5, 19) else raw
    }

    private fun formatUtc(epochMillis: Long): String {
        if (epochMillis <= 0L) return "unknown time"
        return DISPLAY_UTC_FMT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC))
    }
}
