package social.tbone.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import social.tbone.settings.ThemeMode
import javax.inject.Inject

/**
 * Reads the persisted theme + accent and applies them around the app content.
 * Lives at the activity root so a change anywhere re-themes everything.
 */
@Composable
fun ThemeHost(content: @Composable () -> Unit) {
    val viewModel: ThemeViewModel = hiltViewModel()
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentHex by viewModel.accentColor.collectAsStateWithLifecycle()
    BonyTheme(mode = mode, accent = parseHexColor(accentHex)) {
        content()
    }
}

/** Host-level access to the theme preferences so the whole app re-themes. */
@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val appSettings: AppSettings,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DARK)

    /** Custom accent as "#RRGGBB", or null for the default green. */
    val accentColor: StateFlow<String?> = appSettings.accentColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setAccentColor(hex: String?) {
        viewModelScope.launch { appSettings.setAccentColor(hex) }
    }
}
