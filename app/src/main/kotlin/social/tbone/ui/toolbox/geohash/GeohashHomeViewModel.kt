package social.tbone.ui.toolbox.geohash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.tbone.nostr.geohash.GeohashChannelEntry
import social.tbone.settings.AppSettings
import javax.inject.Inject

/**
 * Drives the compact, horizontally-scrollable strip of home-pinned location
 * channels on the home screen, including each channel's unread badge count.
 */
@HiltViewModel
class GeohashHomeViewModel @Inject constructor(
    appSettings: AppSettings,
    private val monitor: GeohashPinnedMonitor,
) : ViewModel() {

    /** Pinned-to-home channels, pinned-first then order. */
    val homeChannels: StateFlow<List<GeohashChannelEntry>> =
        appSettings.geohashChannels
            .map { list -> list.filter { it.pinnedToHome } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Unread counts per channel code (from the background monitor). */
    val unread: StateFlow<Map<String, Int>> = monitor.unread

    /** Clears the badge when the user opens the channel. */
    fun markSeen(code: String) = monitor.markSeen(code)
}
