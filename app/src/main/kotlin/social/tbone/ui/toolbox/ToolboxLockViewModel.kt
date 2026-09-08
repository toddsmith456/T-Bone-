package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ToolboxLockViewModel @Inject constructor(
    private val security: ToolboxSecurity,
) : ViewModel() {

    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    suspend fun verifyPin(input: String): Boolean = security.verifyPin(input)

    suspend fun isDuressPin(input: String): Boolean = security.isDuressPin(input)

    fun unlock() {
        unlocked = true
    }

    fun wipeToolbox() = security.wipeToolbox()
}
