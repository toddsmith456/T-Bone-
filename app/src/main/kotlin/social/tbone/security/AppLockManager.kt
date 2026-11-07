package social.tbone.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class AppLockManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
    @ApplicationContext context: Context,
) {
    private val timeout = BackgroundPinTimeout(context, scope, "app")
    private val attempts = PinAttemptLimiter(context, "app")
    private val verifyMutex = Mutex()
    fun retryAfterMillis(): Long = attempts.remainingMillis()
    val isLocked: StateFlow<Boolean> = timeout.locked
    val isPinEnabled: StateFlow<Boolean> = appSettings.pinEnabled
    val isDuressPinEnabled: StateFlow<Boolean> = appSettings.duressPinEnabled

    init {
        scope.launch {
            appSettings.initialized.first { it }
            timeout.initialize(appSettings.pinEnabled.value, appSettings.pinAutoLockMinutes.value)
            combine(appSettings.pinEnabled, appSettings.pinAutoLockMinutes) { enabled, minutes -> enabled to minutes }
                .collect { timeout.settingsChanged(appSettings.pinEnabled.value, appSettings.pinAutoLockMinutes.value) }
        }
    }

    /** Secret decoy exit must never inherit an unlocked foreground session. */
    suspend fun lockForStealthExit() {
        appSettings.initialized.first { it }
        val enabled = appSettings.getPinEnabled()
        val minutes = appSettings.getPinAutoLockMinutes()
        timeout.initialize(enabled, minutes)
        timeout.settingsChanged(enabled, minutes)
        timeout.lock()
    }

    fun lock() = timeout.lock()
    fun unlock() = timeout.unlock()
    fun onAppBackgrounded() = timeout.onBackground()
    fun onAppForegrounded() = timeout.onForeground()

    suspend fun verifyPin(input: String): Boolean = verifyMutex.withLock {
        if (attempts.remainingMillis() > 0) return@withLock false
        val stored = appSettings.getPinHash() ?: return@withLock false
        val ok = PinHasher.verify(input, stored)
        if (ok) attempts.reset() else attempts.failure()
        // Auto-upgrade legacy hash on successful verify (no extra user step)
        if (ok && PinHasher.isLegacy(stored)) {
            appSettings.setPinHash(PinHasher.hash(input))
        }
        ok
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
        attempts.reset()
        appSettings.setPinHash(PinHasher.hash(pin))
        appSettings.setPinEnabled(true)
        timeout.unlock()
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
        timeout.settingsChanged(false, appSettings.pinAutoLockMinutes.value)
    }


}
