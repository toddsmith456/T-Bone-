package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import social.tbone.webxdc.XdcCustomTool
import javax.inject.Inject

@HiltViewModel
class ToolboxViewModel @Inject constructor(
    private val appSettings: AppSettings,
) : ViewModel() {

    val pinEnabled: StateFlow<Boolean> = appSettings.toolboxPinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val screenshotBlockEnabled: StateFlow<Boolean> = appSettings.toolboxScreenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val appScreenshotBlockEnabled: StateFlow<Boolean> = appSettings.screenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Tool order (ids) — user-reorderable by long-press drag. */
    val toolOrder: StateFlow<List<String>> = appSettings.toolboxToolOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tools backed by saved webxdc mini apps. */
    val xdcCustomTools: StateFlow<List<XdcCustomTool>> = appSettings.xdcCustomTools
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Default order used until the user reorders. */
    val defaultToolOrder: List<String> =
        listOf("webxdc", "notes", "bookmarks", "voice", "geohash", "calendar", "wallet")

    fun setToolOrder(order: List<String>) {
        viewModelScope.launch { appSettings.setToolboxToolOrder(order) }
    }
}
