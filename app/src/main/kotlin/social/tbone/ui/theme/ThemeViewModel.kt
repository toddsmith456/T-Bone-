package social.tbone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.youniversal.theme.YouniversalTheme
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.rememberYouniversalThemeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import social.tbone.settings.ThemeMode
import social.tbone.settings.UiTheme
import javax.inject.Inject

/**
 * Reads the persisted theme + accent and applies them around the app content.
 * Lives at the activity root so a change anywhere re-themes everything.
 *
 * Now supports two engines: the original Bony theme and the Youniversal
 * design system. When Youniversal is selected the whole composition is wrapped
 * in [YouniversalTheme]; otherwise the classic [BonyTheme] is used. Legacy
 * screens that still read `BonyColors.*` remain correct in Youniversal mode
 * because we mirror the Material scheme into `BonyColors` via `applyYouniversal`.
 */
@Composable
fun ThemeHost(content: @Composable () -> Unit) {
    val viewModel: ThemeViewModel = hiltViewModel()
    val uiTheme by viewModel.uiTheme.collectAsStateWithLifecycle()
    val mode by viewModel.themeMode.collectAsStateWithLifecycle()
    val accentHex by viewModel.accentColor.collectAsStateWithLifecycle()
    val youniversalState = rememberYouniversalThemeState()

    if (uiTheme == UiTheme.YOUNIVERSAL) {
        // Youniversal is the primary engine; its fallback is the Bony theme so
        // any Youniversal component rendered while disabled still has a valid
        // MaterialTheme. We also mirror its resolved scheme into BonyColors for
        // backwards-compat with the 200+ places that read BonyColors directly.
        YouniversalTheme(
            state = youniversalState,
            fallback = { fallbackContent ->
                BonyTheme(mode = mode, accent = parseHexColor(accentHex)) {
                    fallbackContent()
                }
            },
        ) {
            // Bridge: keep BonyColors in sync with the Youniversal scheme so
            // old UI (BonyColors.Bg etc.) matches Youniversal's palette.
            val scheme = MaterialTheme.colorScheme
            androidx.compose.runtime.LaunchedEffect(scheme) {
                BonyColors.applyYouniversal(scheme)
            }
            content()
        }
    } else {
        BonyTheme(mode = mode, accent = parseHexColor(accentHex)) {
            content()
        }
    }
}

/** Backwards-compat alias — some call sites still import ThemeHost. */
@Composable
fun AppThemeHost(content: @Composable () -> Unit) = ThemeHost(content)

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

    val uiTheme: StateFlow<UiTheme> = appSettings.uiTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiTheme.BONY)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setAccentColor(hex: String?) {
        viewModelScope.launch { appSettings.setAccentColor(hex) }
    }

    fun setUiTheme(theme: UiTheme) {
        viewModelScope.launch { appSettings.setUiTheme(theme) }
    }
}
