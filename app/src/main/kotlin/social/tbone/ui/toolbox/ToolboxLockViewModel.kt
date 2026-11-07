package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun retryAfterMillis(): Long = security.retryAfterMillis()

    suspend fun verifyPin(input: String): Boolean = withContext(Dispatchers.Default) { security.verifyPin(input) }

    suspend fun isDuressPin(input: String): Boolean = withContext(Dispatchers.Default) { security.isDuressPin(input) }

    fun unlock() {
        toolboxLockManager.unlock()
    }

    fun wipeToolbox() = security.wipeToolbox()
}
