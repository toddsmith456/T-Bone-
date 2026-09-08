package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class ToolboxSettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val security: ToolboxSecurity,
) : ViewModel() {

    val screenshotBlockEnabled: StateFlow<Boolean> = appSettings.toolboxScreenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pinEnabled: StateFlow<Boolean> = appSettings.toolboxPinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val duressPinEnabled: StateFlow<Boolean> = appSettings.toolboxDuressPinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun setScreenshotBlockEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setToolboxScreenshotBlockEnabled(enabled) }
    }

    fun setPin(pin: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (security.setPin(pin)) onDone() else _error.value = "toolbox pin can't match the duress pin"
        }
    }

    fun disablePin() {
        viewModelScope.launch { security.disablePin() }
    }

    fun setDuressPin(pin: String, onDone: () -> Unit) {
        viewModelScope.launch {
            if (security.setDuressPin(pin)) onDone() else _error.value = "duress pin can't match the toolbox pin"
        }
    }

    fun disableDuressPin() {
        viewModelScope.launch { security.disableDuressPin() }
    }

    fun wipeToolbox() = security.wipeToolbox()

    fun clearError() {
        _error.value = null
    }
}
