package social.tbone.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parental screen-time enforcement.
 *
 * Tracks how long the app is in the foreground each day (banked into
 * DataStore, rolling over at midnight). When the daily allowance is used up
 * the app locks and only the parental PIN unlocks it — each successful PIN
 * entry grants a 30-minute grace period, after which it locks again.
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

    private var sessionStartMs = System.currentTimeMillis()
    private var tickerJob: Job? = null

    /** App entered foreground: start banking time and watching the limit. */
    fun onAppForeground() {
        sessionStartMs = System.currentTimeMillis()
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(30_000) // bank every 30s while the app is open
                bankElapsed()
                _locked.value = isOverLimitNow()
            }
        }
        // Immediate check (e.g. returning to an already-over-limit day).
        scope.launch { _locked.value = isOverLimitNow() }
    }

    /** App left foreground: stop the ticker and bank the last slice. */
    fun onAppBackground() {
        tickerJob?.cancel()
        tickerJob = null
        scope.launch { bankElapsed() }
    }

    private suspend fun bankElapsed() {
        val now = System.currentTimeMillis()
        val elapsed = (now - sessionStartMs).coerceAtLeast(0L)
        sessionStartMs = now
        if (elapsed > 0) appSettings.addScreenTimeSeconds(elapsed / 1000)
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
