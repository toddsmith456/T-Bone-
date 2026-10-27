package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.security.ToolboxLockManager
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class ToolboxLockViewModel @Inject constructor(
    private val security: ToolboxSecurity,
    private val toolboxLockManager: ToolboxLockManager,
    appSettings: AppSettings,
) : ViewModel() {

    val keypadScrambled: StateFlow<Boolean> = appSettings.toolboxPinKeypadScrambled
    val isLocked: StateFlow<Boolean> = toolboxLockManager.isLocked

    suspend fun verifyPin(input: String): Boolean = security.verifyPin(input)

    suspend fun isDuressPin(input: String): Boolean = security.isDuressPin(input)

    fun unlock() {
        toolboxLockManager.unlock()
    }

    fun wipeToolbox() = security.wipeToolbox()
}
