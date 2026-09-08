package social.tbone.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    // Start locked on every fresh process; auto-unlocked below if PIN is not enabled.
    // This ensures force-kill + relaunch shows the lock prompt, since onStop() is not
    // guaranteed to run when the process is killed.
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    init {
        scope.launch {
            if (!appSettings.getPinEnabled()) _isLocked.value = false
        }
    }

    val isPinEnabled: StateFlow<Boolean> = appSettings.pinEnabled
    val isDuressPinEnabled: StateFlow<Boolean> = appSettings.duressPinEnabled

    fun lock() {
        if (isPinEnabled.value) _isLocked.value = true
    }

    fun unlock() {
        _isLocked.value = false
    }

    suspend fun verifyPin(input: String): Boolean {
        val stored = appSettings.getPinHash() ?: return false
        return sha256(input) == stored
    }

    /** True when the input matches the duress pin (which wipes data instead of unlocking). */
    suspend fun isDuressPin(input: String): Boolean {
        if (!appSettings.getDuressPinEnabled()) return false
        val stored = appSettings.getDuressPinHash() ?: return false
        return sha256(input) == stored
    }

    suspend fun setPin(pin: String) {
        appSettings.setPinHash(sha256(pin))
        appSettings.setPinEnabled(true)
    }

    /**
     * Sets the duress pin. Refuses a pin that equals the normal unlock PIN —
     * otherwise every normal unlock would wipe the app.
     */
    suspend fun setDuressPin(pin: String): Boolean {
        val unlockHash = appSettings.getPinHash()
        if (unlockHash != null && sha256(pin) == unlockHash) return false
        appSettings.setDuressPinHash(sha256(pin))
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
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
