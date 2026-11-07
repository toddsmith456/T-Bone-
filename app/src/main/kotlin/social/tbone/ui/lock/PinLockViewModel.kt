package social.tbone.ui.lock

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.security.AppLockManager
import social.tbone.security.DataWiper
import social.tbone.settings.AppSettings
import javax.inject.Inject

@HiltViewModel
class PinLockViewModel @Inject constructor(
    private val lockManager: AppLockManager,
    private val dataWiper: DataWiper,
    appSettings: AppSettings,
) : ViewModel() {

    val keypadScrambled: StateFlow<Boolean> = appSettings.appPinKeypadScrambled

    fun retryAfterMillis(): Long = lockManager.retryAfterMillis()

    suspend fun verifyPin(input: String): Boolean = withContext(Dispatchers.Default) { lockManager.verifyPin(input) }

    /**
     * True when the entered pin is the duress pin (meaning we should wipe
     * instead of unlock). Returns false when duress is disabled.
     */
    suspend fun isDuressPin(input: String): Boolean = withContext(Dispatchers.Default) { lockManager.isDuressPin(input) }

    /** Completely wipes the app's data and restarts into onboarding. */
    fun wipeData() = dataWiper.wipeAndRestart()

    fun unlock() = lockManager.unlock()
}
