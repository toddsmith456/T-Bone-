package social.tbone.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Conservative validation of a persisted background interval across process restarts. */
internal fun backgroundTimeoutExpired(
    startElapsed: Long, nowElapsed: Long, startWall: Long, nowWall: Long,
    timeoutMillis: Long, sameBoot: Boolean,
): Boolean {
    if (!sameBoot || startElapsed < 0 || startWall <= 0 || timeoutMillis <= 0) return true
    val elapsed = nowElapsed - startElapsed
    val wall = nowWall - startWall
    // A clock change, reboot, or malformed marker must never bypass the PIN.
    if (elapsed < 0 || wall < 0 || abs(wall - elapsed) > 120_000L) return true
    return elapsed >= timeoutMillis
}

internal interface PinClock {
    fun elapsed(): Long
    fun wall(): Long
    fun bootCount(): Int
}

private class AndroidPinClock(context: Context) : PinClock {
    private val resolver = context.contentResolver
    override fun elapsed() = SystemClock.elapsedRealtime()
    override fun wall() = System.currentTimeMillis()
    override fun bootCount() = runCatching { Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
}

/**
 * A PIN stays unlocked while foregrounded. A synchronous, short preferences
 * commit at background records a grace period that survives process death.
 * Missing/invalid markers fail closed, including a process killed in foreground.
 * All transitions are serialized because the injected app scope uses IO threads.
 */
internal class BackgroundPinTimeout(
    context: Context,
    private val scope: CoroutineScope,
    private val key: String,
    private val clock: PinClock = AndroidPinClock(context),
) {
    private val prefs = context.getSharedPreferences("pin_background_timeout", Context.MODE_PRIVATE)
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var initialized = false
    private var enabled = false
    private var foreground = false
    private var timeoutMillis = 5 * 60_000L
    private var startedElapsed: Long? = null
    private var job: Job? = null
    private fun clearMarker(): Boolean = prefs.edit().remove("${key}_elapsed")
        .remove("${key}_wall").remove("${key}_boot").commit()

    @Synchronized
    fun initialize(pinEnabled: Boolean, minutes: Int) {
        if (initialized) return
        enabled = pinEnabled
        timeoutMillis = minutes.coerceIn(1, 30) * 60_000L
        initialized = true
        if (!enabled) {
            _locked.value = false
        } else {
            val elapsed = prefs.getLong("${key}_elapsed", -1L)
            val wall = prefs.getLong("${key}_wall", 0L)
            val boot = prefs.getInt("${key}_boot", -1)
            val nowBoot = clock.bootCount()
            val sameBoot = boot >= 0 && nowBoot >= 0 && boot == nowBoot
            _locked.value = backgroundTimeoutExpired(elapsed, clock.elapsed(), wall,
                clock.wall(), timeoutMillis, sameBoot)
        }
        if (foreground) {
            if (!clearMarker() && enabled) _locked.value = true
        } else if (enabled && !_locked.value) {
            // Already backgrounded while initialization completed: start a fresh
            // timer only if a valid prior grace period was restored.
            startBackground()
        }
    }

    @Synchronized
    fun settingsChanged(pinEnabled: Boolean, minutes: Int) {
        if (!initialized) return
        enabled = pinEnabled
        timeoutMillis = minutes.coerceIn(1, 30) * 60_000L
        if (!enabled) {
            job?.cancel(); startedElapsed = null; _locked.value = false; clearMarker()
        } else if (!foreground && !_locked.value) {
            startedElapsed?.let { schedule(it) }
        }
    }

    @Synchronized
    fun unlock() {
        if (!clearMarker()) { _locked.value = true; return }
        job?.cancel(); startedElapsed = null; _locked.value = false
        if (initialized && enabled && !foreground) startBackground()
    }

    @Synchronized
    fun lock() {
        job?.cancel(); startedElapsed = null
        if (enabled) _locked.value = true
        clearMarker()
    }

    @Synchronized
    fun onForeground() {
        foreground = true
        if (!initialized) return
        job?.cancel()
        val started = startedElapsed
        if (enabled && !_locked.value && started != null) {
            val elapsed = clock.elapsed() - started
            if (elapsed < 0 || elapsed >= timeoutMillis) _locked.value = true
        }
        startedElapsed = null
        if (!clearMarker() && enabled) _locked.value = true
    }

    @Synchronized
    fun onBackground() {
        if (!foreground) return
        foreground = false
        if (initialized && enabled && !_locked.value) startBackground()
    }

    private fun startBackground() {
        val nowElapsed = clock.elapsed()
        val stored = prefs.edit().putLong("${key}_elapsed", nowElapsed)
            .putLong("${key}_wall", clock.wall()).putInt("${key}_boot", clock.bootCount()).commit()
        if (!stored) { _locked.value = true; return }
        startedElapsed = nowElapsed
        schedule(nowElapsed)
    }

    private fun schedule(start: Long) {
        job?.cancel()
        val delayMillis = (timeoutMillis - (clock.elapsed() - start)).coerceAtLeast(0L)
        job = scope.launch {
            delay(delayMillis)
            synchronized(this@BackgroundPinTimeout) {
                if (!foreground && enabled && startedElapsed == start &&
                    clock.elapsed() - start >= timeoutMillis) _locked.value = true
            }
        }
    }
}
