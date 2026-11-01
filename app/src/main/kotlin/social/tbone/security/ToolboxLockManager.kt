package social.tbone.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolboxLockManager @Inject constructor(
    private val appSettings: AppSettings,
    private val scope: CoroutineScope,
    @ApplicationContext context: Context,
) {
    private val timeout = BackgroundPinTimeout(context, scope, "toolbox")
    val isLocked: StateFlow<Boolean> = timeout.locked
    init {
        scope.launch {
            appSettings.initialized.first { it }
            timeout.initialize(appSettings.toolboxPinEnabled.value, appSettings.toolboxPinAutoLockMinutes.value)
            combine(appSettings.toolboxPinEnabled, appSettings.toolboxPinAutoLockMinutes) { enabled, minutes -> enabled to minutes }
                .collect { (enabled, minutes) -> timeout.settingsChanged(enabled, minutes) }
        }
    }
    fun unlock() = timeout.unlock()
    fun lock() = timeout.lock()
    fun onAppBackgrounded() = timeout.onBackground()
    fun onAppForegrounded() = timeout.onForeground()
}
