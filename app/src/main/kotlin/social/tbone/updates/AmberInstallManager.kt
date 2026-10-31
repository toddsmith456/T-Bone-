package social.tbone.updates

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val AMBER_APP_ID = "com.greenart7c3.nostrsigner"

data class AmberInstallState(val isInstalled: Boolean = false)

/** Only detects installation. Downloads are handed off to Amber's official site. */
@Singleton
class AmberInstallManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val _state = MutableStateFlow(AmberInstallState(isAmberInstalled()))
    val state = _state.asStateFlow()

    fun isAmberInstalled(): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(AMBER_APP_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun refreshInstalled(): Boolean = isAmberInstalled().also { _state.value = AmberInstallState(it) }
}
