package social.tbone.ui.lock

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.security.AppLockManager
import social.tbone.security.DataWiper
import javax.inject.Inject

@HiltViewModel
class PinLockViewModel @Inject constructor(
    private val lockManager: AppLockManager,
    private val dataWiper: DataWiper,
) : ViewModel() {

    suspend fun verifyPin(input: String): Boolean = lockManager.verifyPin(input)

    /**
     * True when the entered pin is the duress pin (meaning we should wipe
     * instead of unlock). Returns false when duress is disabled.
     */
    suspend fun isDuressPin(input: String): Boolean = lockManager.isDuressPin(input)

    /** Completely wipes the app's data and restarts into onboarding. */
    fun wipeData() = dataWiper.wipeAndRestart()

    fun unlock() = lockManager.unlock()
}
