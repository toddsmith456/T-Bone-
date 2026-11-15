package social.tbone.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parental screen-time enforcement.
 *
 * Tracks how long the app is in the foreground each day (banked into
 * DataStore, rolling over at midnight) **only when Screen Time is enabled**.
 * When the daily allowance is used up the app locks and only the parental PIN
 * unlocks it — each successful PIN entry grants a 30-minute grace period,
 * after which it locks again.
 */
@Singleton
class ScreenTimeManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    /** True when the app is locked by the screen-time limit. */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Grace granted per parental unlock, ms. */
    private val GRACE_MS = 30L * 60L * 1000L

    private val stateLock = Any()
    @Volatile private var inForeground = false
    private var sessionStartMs = System.currentTimeMillis()
    private var tickerJob: Job? = null

    init {
        // Reactively start/stop the ticker when the parent toggles Screen Time
        // while the app is already open, so disabled installs perform zero
        // periodic DataStore writes.
        scope.launch {
            appSettings.screenTimeEnabled.collect { enabled ->
                if (enabled) {
                    if (inForeground) startTickerLocked()
                    refreshLockState()
                } else {
                    stopTickerLocked()
                    _locked.value = false
                }
            }
        }
    }

    /** App entered foreground: start banking time only if Screen Time is enabled. */
    fun onAppForeground() {
        inForeground = true
        if (!appSettings.screenTimeEnabled.value) {
            stopTickerLocked()
            _locked.value = false
            return
        }
        startTickerLocked()
        // Immediate check (e.g. returning to an already-over-limit day).
        scope.launch { refreshLockState() }
    }

    /** App left foreground: stop the ticker and bank the last slice if enabled. */
    fun onAppBackground() {
        inForeground = false
        val wasTracking = stopTickerLocked()
        if (wasTracking && appSettings.screenTimeEnabled.value) {
            scope.launch {
                runCatching { bankElapsed() }
                    .onFailure { Timber.w(it, "Failed to bank screen time on background") }
            }
        }
    }

    private fun startTickerLocked() {
        synchronized(stateLock) {
            sessionStartMs = System.currentTimeMillis()
            tickerJob?.cancel()
            tickerJob = scope.launch {
                while (true) {
                    delay(30_000) // bank every 30s while the app is open & enabled
                    if (!appSettings.screenTimeEnabled.value) {
                        _locked.value = false
                        break
                    }
                    runCatching {
                        bankElapsed()
                        _locked.value = isOverLimitNow()
                    }.onFailure {
                        Timber.w(it, "Screen time tick failed")
                    }
                }
            }
        }
    }

    private fun stopTickerLocked(): Boolean = synchronized(stateLock) {
        val hadJob = tickerJob != null
        tickerJob?.cancel()
        tickerJob = null
        hadJob
    }

    private suspend fun refreshLockState() {
        runCatching { _locked.value = isOverLimitNow() }
            .onFailure { Timber.w(it, "Screen time limit check failed") }
    }

    private suspend fun bankElapsed() {
        if (!appSettings.screenTimeEnabled.value) return
        val elapsedSeconds = synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val elapsed = (now - sessionStartMs).coerceAtLeast(0L)
            sessionStartMs = now
            elapsed / 1000
        }
        if (elapsedSeconds > 0) appSettings.addScreenTimeSeconds(elapsedSeconds)
    }

    private suspend fun isOverLimitNow(): Boolean {
        if (!appSettings.screenTimeEnabled.value) return false
        val graceUntil = appSettings.getScreenTimeGraceUntil()
        if (System.currentTimeMillis() < graceUntil) return false
        val usedSec = appSettings.getScreenTimeUsedSeconds()
        val limitSec = appSettings.screenTimeMinutes.value * 60L
        return usedSec >= limitSec
    }

    /**
     * Verifies the parental pin; on success grants 30 extra minutes and
     * unlocks. Returns true only when the pin was correct.
     */
    suspend fun unlockWithParentalPin(pin: String): Boolean {
        val stored = appSettings.getParentalPinHash() ?: return false
        if (sha256(pin) != stored) return false
        appSettings.setScreenTimeGraceUntil(System.currentTimeMillis() + GRACE_MS)
        _locked.value = false
        return true
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
