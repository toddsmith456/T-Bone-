package social.tbone.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import social.tbone.security.PinHasher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class AppLockManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    init {
        scope.launch {
            if (!appSettings.getPinEnabled()) _isLocked.value = false
            else {
                val last = appSettings.getPinLastUnlockMillis()
                val minutes = appSettings.getPinAutoLockMinutes()
                evaluateLock(last, minutes * 60_000L)
            }
        }
        // React to settings changes while app is running
        scope.launch {
            combine(
                appSettings.pinEnabled,
                appSettings.pinLastUnlockMillis,
                appSettings.pinAutoLockMinutes,
            ) { enabled, last, minutes -> Triple(enabled, last, minutes) }
                .collect { (enabled, last, minutes) ->
                    if (!enabled) {
                        _isLocked.value = false
                        autoLockJob?.cancel()
                    } else {
                        if (_isLocked.value) {
                            // If currently locked, check if last unlock now makes it unlocked (e.g. just unlocked)
                            evaluateLock(last, minutes * 60_000L)
                        } else {
                            // Currently unlocked — reschedule with new timeout/last
                            val elapsed = System.currentTimeMillis() - last
                            val timeoutMs = minutes * 60_000L
                            if (last == 0L || elapsed >= timeoutMs) {
                                _isLocked.value = true
                                autoLockJob?.cancel()
                            } else {
                                schedule(timeoutMs - elapsed)
                            }
                        }
                    }
                }
        }
    }

    private var autoLockJob: Job? = null

    private fun evaluateLock(lastUnlockMillis: Long, timeoutMs: Long) {
        if (lastUnlockMillis == 0L) {
            _isLocked.value = true
            autoLockJob?.cancel()
            return
        }
        val elapsed = System.currentTimeMillis() - lastUnlockMillis
        if (elapsed >= timeoutMs) {
            _isLocked.value = true
            autoLockJob?.cancel()
        } else {
            _isLocked.value = false
            schedule(timeoutMs - elapsed)
        }
    }

    private fun schedule(delayMs: Long) {
        autoLockJob?.cancel()
        autoLockJob = scope.launch {
            delay(delayMs.coerceAtLeast(0))
            if (appSettings.pinEnabled.value) _isLocked.value = true
        }
    }

    val isPinEnabled: StateFlow<Boolean> = appSettings.pinEnabled
    val isDuressPinEnabled: StateFlow<Boolean> = appSettings.duressPinEnabled

    fun lock() {
        if (isPinEnabled.value) _isLocked.value = true
        autoLockJob?.cancel()
    }

    fun unlock() {
        _isLocked.value = false
        scope.launch { appSettings.setPinLastUnlockMillis(System.currentTimeMillis()) }
        val minutes = appSettings.pinAutoLockMinutes.value
        schedule(minutes * 60_000L)
    }

    /** Called when app goes to background — do not lock immediately, let timeout decide. */
    fun onAppBackgrounded() {
        // No-op: timeout continues via scheduled job. If already timed out, job will have locked.
        // We re-evaluate in case timeout elapsed while backgrounded is checked on foreground.
    }

    /** Called when app returns to foreground — re-check timeout. */
    fun onAppForegrounded() {
        scope.launch {
            if (!appSettings.getPinEnabled()) {
                _isLocked.value = false
                return@launch
            }
            val last = appSettings.getPinLastUnlockMillis()
            val minutes = appSettings.getPinAutoLockMinutes()
            evaluateLock(last, minutes * 60_000L)
        }
    }

    suspend fun verifyPin(input: String): Boolean {
        val stored = appSettings.getPinHash() ?: return false
        val ok = PinHasher.verify(input, stored)
        // Auto-upgrade legacy hash on successful verify (no extra user step)
        if (ok && PinHasher.isLegacy(stored)) {
            appSettings.setPinHash(PinHasher.hash(input))
        }
        return ok
    }

    suspend fun isDuressPin(input: String): Boolean {
        if (!appSettings.getDuressPinEnabled()) return false
        val stored = appSettings.getDuressPinHash() ?: return false
        val ok = PinHasher.verify(input, stored)
        if (ok && PinHasher.isLegacy(stored)) {
            appSettings.setDuressPinHash(PinHasher.hash(input))
        }
        return ok
    }

    suspend fun setPin(pin: String) {
        appSettings.setPinHash(PinHasher.hash(pin))
        appSettings.setPinEnabled(true)
        appSettings.setPinLastUnlockMillis(System.currentTimeMillis())
        _isLocked.value = false
        val minutes = appSettings.getPinAutoLockMinutes()
        schedule(minutes * 60_000L)
    }

    suspend fun setDuressPin(pin: String): Boolean {
        val unlockHash = appSettings.getPinHash()
        // Compare via hashes — prevent duress == unlock (compare normalized): check via verify
        if (unlockHash != null && PinHasher.verify(pin, unlockHash)) return false
        // Also prevent legacy collision if unlockHash is legacy sha256 of pin
        appSettings.setDuressPinHash(PinHasher.hash(pin))
        appSettings.setDuressPinEnabled(true)
        return true
    }

    suspend fun disableDuressPin() {
        appSettings.setDuressPinEnabled(false)
        appSettings.setDuressPinHash("")
    }

    suspend fun disablePin() {
        appSettings.setPinEnabled(false)
        _isLocked.value = false
        autoLockJob?.cancel()
    }


}
