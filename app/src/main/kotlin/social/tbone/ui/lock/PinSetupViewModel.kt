package social.tbone.ui.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import social.tbone.settings.AppSettings
import social.tbone.security.AppLockManager
import javax.inject.Inject

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val lockManager: AppLockManager,
    appSettings: AppSettings,
) : ViewModel() {

    val appPinKeypadScrambled: StateFlow<Boolean> = appSettings.appPinKeypadScrambled

    suspend fun setPin(pin: String) = lockManager.setPin(pin)

    suspend fun setDuressPin(pin: String): Boolean = lockManager.setDuressPin(pin)
}
