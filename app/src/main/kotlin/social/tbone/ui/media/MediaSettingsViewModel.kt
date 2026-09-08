package social.tbone.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import social.tbone.settings.AvatarMode
import social.tbone.settings.ImageLoadMode
import javax.inject.Inject

/**
 * Exposes the media/appearance preferences (inline images, avatars) to the nav
 * host so they can be provided as CompositionLocals. Reads come from the same
 * AppSettings singleton the settings screen writes to, so both stay in sync
 * automatically.
 */
@HiltViewModel
class MediaSettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
) : ViewModel() {

    val imageLoadMode: StateFlow<ImageLoadMode> = appSettings.imageLoadMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageLoadMode.ON)

    val avatarMode: StateFlow<AvatarMode> = appSettings.avatarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarMode.REGULAR)

    val avatarAnimated: StateFlow<Boolean> = appSettings.avatarAnimated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setImageLoadMode(mode: ImageLoadMode) {
        viewModelScope.launch { appSettings.setImageLoadMode(mode) }
    }

    fun setAvatarMode(mode: AvatarMode) {
        viewModelScope.launch { appSettings.setAvatarMode(mode) }
    }

    fun setAvatarAnimated(animated: Boolean) {
        viewModelScope.launch { appSettings.setAvatarAnimated(animated) }
    }
}
