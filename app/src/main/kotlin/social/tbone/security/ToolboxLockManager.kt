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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolboxLockManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
) {
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var autoLockJob: Job? = null

    init {
        scope.launch {
            if (!appSettings.getToolboxPinEnabled()) _isLocked.value = false
            else {
                val last = appSettings.getToolboxPinLastUnlockMillis()
                val minutes = appSettings.getToolboxPinAutoLockMinutes()
                evaluateLock(last, minutes * 60_000L)
            }
        }
        scope.launch {
            combine(
                appSettings.toolboxPinEnabled,
                appSettings.toolboxPinLastUnlockMillis,
                appSettings.toolboxPinAutoLockMinutes,
            ) { enabled, last, minutes -> Triple(enabled, last, minutes) }
                .collect { (enabled, last, minutes) ->
                    if (!enabled) {
                        _isLocked.value = false
                        autoLockJob?.cancel()
                    } else {
                        if (_isLocked.value) {
                            evaluateLock(last, minutes * 60_000L)
                        } else {
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
            if (appSettings.toolboxPinEnabled.value) _isLocked.value = true
        }
    }

    fun unlock() {
        _isLocked.value = false
        scope.launch { appSettings.setToolboxPinLastUnlockMillis(System.currentTimeMillis()) }
        val minutes = appSettings.toolboxPinAutoLockMinutes.value
        schedule(minutes * 60_000L)
    }

    fun lock() {
        if (appSettings.toolboxPinEnabled.value) _isLocked.value = true
        autoLockJob?.cancel()
    }

    fun onAppBackgrounded() { /* timeout via job */ }

    fun onAppForegrounded() {
        scope.launch {
            if (!appSettings.getToolboxPinEnabled()) {
                _isLocked.value = false
                return@launch
            }
            val last = appSettings.getToolboxPinLastUnlockMillis()
            val minutes = appSettings.getToolboxPinAutoLockMinutes()
            evaluateLock(last, minutes * 60_000L)
        }
    }
}
