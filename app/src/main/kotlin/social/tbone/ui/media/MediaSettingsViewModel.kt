package social.tbone.ui.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import social.tbone.settings.AppSettings
import social.tbone.settings.AvatarMode
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality
import javax.inject.Inject

/**
 * Exposes the media/appearance preferences (inline images, video, avatars) to the nav
 * host so they can be provided as CompositionLocals. Reads come from the same
 * AppSettings singleton the settings screen writes to, so both stay in sync
 * automatically.
 */
@HiltViewModel
class MediaSettingsViewModel @Inject constructor(
    appSettings: AppSettings,
) : ViewModel() {

    val imageLoadMode: StateFlow<ImageLoadMode> = appSettings.imageLoadMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageLoadMode.ON)

    val videoQuality: StateFlow<VideoQuality> = appSettings.videoQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoQuality.REGULAR)

    val videoPlaybackMode: StateFlow<VideoPlaybackMode> = appSettings.videoPlaybackMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoPlaybackMode.LOAD_ON_TAP)

    val videoThumbnails: StateFlow<Boolean> = appSettings.videoThumbnails
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val mediaFittedLayout: StateFlow<Boolean> = appSettings.mediaFittedLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val avatarMode: StateFlow<AvatarMode> = appSettings.avatarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarMode.REGULAR)

    val avatarAnimated: StateFlow<Boolean> = appSettings.avatarAnimated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
}
