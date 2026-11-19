package social.tbone.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class LogFormatterTest {

    // ── performance section: separate from crash data ──────────────────────

    private fun report(options: LogExportOptions, performance: String? = "=== PERFORMANCE (this session) ===\nFeed loads: 3") =
        LogFormatter.formatReport(
            appVersion = "0.6.2 (78)",
            androidRelease = "14",
            sdkInt = 34,
            nowMillis = now,
            options = options,
            crashes = emptyList(),
            systemExits = emptyList(),
            logRecords = emptyList(),
            zoneId = utc,
            performanceSection = performance,
        )

    @Test fun `performance section is included by default and sits above crash content`() {
        val text = report(LogExportOptions(includePerformance = true))
        assertTrue(text.contains("PERFORMANCE (this session)"))
        assertTrue(
            "performance must come before the crash sections so it is seen first",
            text.indexOf("PERFORMANCE") < text.indexOf("APP LOG").let { if (it < 0) Int.MAX_VALUE else it },
        )
    }

    @Test fun `performance can be turned off without touching crash content`() {
        val withPerf = report(LogExportOptions(includePerformance = true))
        val withoutPerf = report(LogExportOptions(includePerformance = false))
        assertFalse(withoutPerf.contains("PERFORMANCE (this session)"))
        assertTrue("crash report structure must be unaffected", withoutPerf.contains("T-Bone Diagnostics Report"))
        assertTrue(withPerf.length > withoutPerf.length)
    }

    @Test fun `crashes-only exports can still carry performance`() {
        val text = report(LogExportOptions(crashesOnly = true, includePerformance = true))
        assertTrue(text.contains("Scope: Crashes only"))
        assertTrue(text.contains("PERFORMANCE (this session)"))
    }

    private val utc = ZoneOffset.UTC
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val now = Instant.parse("2026-11-14T18:00:00Z").toEpochMilli()

    private fun logLine(offsetMillis: Long, level: Char, tag: String, msg: String): String {
        val ts = fmt.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(now + offsetMillis), utc))
        return "$ts $level/$tag: $msg"
    }

    @Test fun `parses multiline timber logs with stack traces`() {
        val raw = buildString {
            appendLine(logLine(-10_000, 'I', "RelayPool", "Connected wss://relay.example"))
            appendLine(logLine(-5_000, 'E', "FeedVM", "Sync failed"))
            appendLine("java.lang.IllegalStateException: boom")
            appendLine("\tat social.tbone.ui.feed.FeedViewModel.load(FeedViewModel.kt:42)")
            appendLine(logLine(-1_000, 'W', "FrameMonitor", "Slow frame 48ms"))
        }

        val parsed = LogFormatter.parseLogText(raw, zoneId = utc)
        assertEquals(3, parsed.size)
        assertEquals('I', parsed[0].level)
        assertEquals("RelayPool", parsed[0].tag)
        assertEquals('E', parsed[1].level)
        assertEquals(2, parsed[1].continuationLines.size)
        assertEquals('W', parsed[2].level)
    }

    @Test fun `time range filters crashes system exits and logs accurately`() {
        val hour = 60L * 60 * 1000
        val day = 24L * hour

        val crashes = listOf(
            CrashRecord(now - 2 * hour, "Uncaught exception at 2026-11-14T16:00:00Z on thread main\nRuntimeException: recent"),
            CrashRecord(now - 3 * day, "Uncaught exception at 2026-11-11T18:00:00Z on thread main\nRuntimeException: last-week"),
            CrashRecord(now - 15 * day, "Uncaught exception at 2026-10-30T18:00:00Z on thread main\nRuntimeException: last-month"),
            CrashRecord(now - 45 * day, "Uncaught exception at 2026-09-30T18:00:00Z on thread main\nRuntimeException: ancient"),
        )
        val exits = listOf(
            SystemExitRecord(now - 4 * hour, "LOW_MEMORY (OOM / LMK kill)", "foreground", 0, "lmk", 240_000, 310_000),
            SystemExitRecord(now - 10 * day, "CRASH_NATIVE (C/C++ signal)", "foreground", 11, "sigsegv", 190_000, 220_000),
        )
        val rawLogs = buildString {
            appendLine(logLine(-40 * day, 'I', "App", "very-old-info"))
            appendLine(logLine(-12 * day, 'E', "App", "month-error"))
            appendLine(logLine(-2 * day, 'I', "App", "week-info"))
            appendLine(logLine(-1 * hour, 'I', "App", "day-info"))
        }
        val logs = LogFormatter.parseLogText(rawLogs, zoneId = utc)

        val dayCounts = LogFormatter.summarize(now, LogExportOptions(LogTimeRange.LAST_DAY), crashes, exits, logs)
        assertEquals(1, dayCounts.uncaughtCrashes)
        assertEquals(1, dayCounts.systemExits)
        assertEquals(0, dayCounts.errors)
        assertEquals(1, dayCounts.logEntries)

        val weekCounts = LogFormatter.summarize(now, LogExportOptions(LogTimeRange.LAST_WEEK), crashes, exits, logs)
        assertEquals(2, weekCounts.uncaughtCrashes)
        assertEquals(1, weekCounts.systemExits)
        assertEquals(2, weekCounts.logEntries)

        val monthCounts = LogFormatter.summarize(now, LogExportOptions(LogTimeRange.LAST_MONTH), crashes, exits, logs)
        assertEquals(3, monthCounts.uncaughtCrashes)
        assertEquals(2, monthCounts.systemExits)
        assertEquals(1, monthCounts.errors)
        assertEquals(3, monthCounts.logEntries)

        val allCounts = LogFormatter.summarize(now, LogExportOptions(LogTimeRange.ALL_TIME), crashes, exits, logs)
        assertEquals(4, allCounts.uncaughtCrashes)
        assertEquals(2, allCounts.systemExits)
        assertEquals(4, allCounts.logEntries)
    }

    @Test fun `crashes only excludes info and warning noise while keeping crashes system kills and errors`() {
        val rawLogs = buildString {
            appendLine(logLine(-30_000, 'I', "RelayPool", "routine-info-noise"))
            appendLine(logLine(-20_000, 'W', "FrameMonitor", "routine-warn-noise"))
            appendLine(logLine(-10_000, 'E', "NotificationsRepo", "real-error-message"))
        }
        val logs = LogFormatter.parseLogText(rawLogs, zoneId = utc)
        val crashes = listOf(
            CrashRecord(now - 5_000, "Uncaught exception at 2026-11-14T17:59:55Z on thread main\nIllegalStateException: fatal-crash"),
        )
        val exits = listOf(
            SystemExitRecord(now - 3_000, "CRASH_NATIVE (C/C++ signal)", "foreground", 11, "SIGSEGV in litert", 180_000, 210_000),
        )

        val report = LogFormatter.formatReport(
            appVersion = "0.5.9 (75)",
            androidRelease = "14",
            sdkInt = 34,
            nowMillis = now,
            options = LogExportOptions(range = LogTimeRange.LAST_DAY, crashesOnly = true),
            crashes = crashes,
            systemExits = exits,
            logRecords = logs,
            zoneId = utc,
        )

        assertTrue(report.contains("Scope: Crashes only"))
        assertTrue(report.contains("fatal-crash"))
        assertTrue(report.contains("CRASH_NATIVE"))
        assertTrue(report.contains("SIGSEGV in litert"))
        assertTrue(report.contains("real-error-message"))
        assertFalse(report.contains("routine-info-noise"))
        assertFalse(report.contains("routine-warn-noise"))
    }

    @Test fun `collapses consecutive duplicate log lines`() {
        val rawLogs = buildString {
            repeat(15) { idx ->
                appendLine(logLine(-20_000 + idx * 1_000L, 'I', "RelayPool", "Reconnect scheduled"))
            }
            appendLine(logLine(-1_000, 'I', "RelayPool", "Connected"))
        }
        val logs = LogFormatter.parseLogText(rawLogs, zoneId = utc)
        val collapsed = LogFormatter.collapseConsecutive(logs)

        assertEquals(2, collapsed.size)
        assertEquals(15, collapsed[0].count)
        assertEquals(1, collapsed[1].count)

        val report = LogFormatter.formatReport(
            appVersion = "0.5.9 (75)",
            androidRelease = "14",
            sdkInt = 34,
            nowMillis = now,
            options = LogExportOptions(range = LogTimeRange.LAST_DAY, crashesOnly = false),
            crashes = emptyList(),
            systemExits = emptyList(),
            logRecords = logs,
            zoneId = utc,
        )
        assertTrue(report.contains("(x15,"))
        assertTrue(report.contains("Connected"))
    }

    @Test fun `deduplicates identical crash from last-crash and history dir`() {
        val payload = "Uncaught exception at 2026-11-14T17:55:00Z on thread main\nRuntimeException: duplicate-boom"
        val ts = LogFormatter.parseCrashTimestamp(payload, now)
        val crashes = listOf(CrashRecord(ts, payload), CrashRecord(ts, payload))

        val counts = LogFormatter.summarize(now, LogExportOptions(LogTimeRange.LAST_DAY, crashesOnly = true), crashes, emptyList(), emptyList())
        assertEquals(1, counts.uncaughtCrashes)
    }

    @Test fun `compacts huge stack traces while preserving app frames and causes`() {
        val hugeTrace = buildString {
            appendLine("java.lang.RuntimeException: top failure")
            repeat(50) { i -> appendLine("\tat kotlinx.coroutines.Dispatcher.run(Dispatcher.kt:$i)") }
            appendLine("\tat social.tbone.ui.feed.FeedViewModel.refresh(FeedViewModel.kt:123)")
            appendLine("Caused by: java.lang.IllegalArgumentException: inner cause")
            repeat(40) { i -> appendLine("\tat android.os.Handler.dispatch(Handler.java:$i)") }
            appendLine("\tat social.tbone.logging.LogRepository.load(LogRepository.kt:55)")
        }

        val compacted = LogFormatter.compactStackTrace(hugeTrace, maxFramesPerSection = 5)
        assertTrue(compacted.contains("java.lang.RuntimeException: top failure"))
        assertTrue(compacted.contains("social.tbone.ui.feed.FeedViewModel.refresh"))
        assertTrue(compacted.contains("Caused by: java.lang.IllegalArgumentException: inner cause"))
        assertTrue(compacted.contains("social.tbone.logging.LogRepository.load"))
        assertTrue(compacted.contains("framework frames omitted"))
        assertTrue(compacted.lines().size < 30)
    }

    @Test fun `bounds output strictly to maxChars while preserving crashes and newest logs`() {
        val rawLogs = buildString {
            repeat(300) { idx ->
                appendLine(logLine(-300_000 + idx * 1_000L, 'I', "Feed", "log-entry-$idx " + "x".repeat(80)))
            }
        }
        val logs = LogFormatter.parseLogText(rawLogs, zoneId = utc)
        val crashes = listOf(
            CrashRecord(now - 5_000, "Uncaught exception at 2026-11-14T17:59:55Z on thread main\nRuntimeException: must-keep-crash"),
        )

        val report = LogFormatter.formatReport(
            appVersion = "0.5.9 (75)",
            androidRelease = "14",
            sdkInt = 34,
            nowMillis = now,
            options = LogExportOptions(range = LogTimeRange.LAST_DAY, crashesOnly = false, maxChars = 4_000),
            crashes = crashes,
            systemExits = emptyList(),
            logRecords = logs,
            zoneId = utc,
        )

        assertTrue("Report length ${report.length} exceeded bound", report.length <= 4_000)
        assertTrue("Crash must never be crowded out", report.contains("must-keep-crash"))
        assertTrue("Newest log entry should be preserved", report.contains("log-entry-299"))
        assertTrue("Truncation notice should be shown", report.contains("omitted"))
    }

    @Test fun `empty crashes report gives clear message`() {
        val report = LogFormatter.formatReport(
            appVersion = "0.5.9 (75)",
            androidRelease = "14",
            sdkInt = 34,
            nowMillis = now,
            options = LogExportOptions(range = LogTimeRange.LAST_DAY, crashesOnly = true),
            crashes = emptyList(),
            systemExits = emptyList(),
            logRecords = emptyList(),
            zoneId = utc,
        )
        assertTrue(report.contains("No crashes, system kills, or errors recorded in this time range."))
    }
}
