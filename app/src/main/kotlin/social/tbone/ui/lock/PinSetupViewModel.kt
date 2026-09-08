package social.tbone.ui.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.security.AppLockManager
import javax.inject.Inject

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val lockManager: AppLockManager,
) : ViewModel() {

    suspend fun setPin(pin: String) = lockManager.setPin(pin)

    suspend fun setDuressPin(pin: String): Boolean = lockManager.setDuressPin(pin)
}
