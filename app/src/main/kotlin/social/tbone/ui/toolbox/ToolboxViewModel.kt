package social.tbone.ui.toolbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import social.tbone.settings.AppSettings
import social.tbone.tokens.OfferStatus
import social.tbone.tokens.SteakRepository
import javax.inject.Inject

@HiltViewModel
class ToolboxViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val steakRepository: SteakRepository,
) : ViewModel() {

    /**
     * Redeem the pending-steak dot on demand.
     *
     * The dot used to piggyback on a live steak subscription that existed for the
     * whole process. That subscription now only runs while a steak surface is
     * open, so the badge does one short request when the toolbox is opened
     * instead of holding four filters on every relay for the whole session.
     */
    fun refreshPendingSteaks() = steakRepository.refreshPendingBadge()

    val hasPendingSteaks: StateFlow<Boolean> = steakRepository.incomingOffers
        .map { offers ->
            val now = System.currentTimeMillis() / 1000L
            offers.any { it.statusAt(now) == OfferStatus.CLAIMABLE }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val pinEnabled: StateFlow<Boolean> = appSettings.toolboxPinEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val screenshotBlockEnabled: StateFlow<Boolean> = appSettings.toolboxScreenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val appScreenshotBlockEnabled: StateFlow<Boolean> = appSettings.screenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Tool order (ids) — user-reorderable by long-press drag. */
    val toolOrder: StateFlow<List<String>> = appSettings.toolboxToolOrder
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Default order used until the user reorders. */
    val defaultToolOrder: List<String> =
        listOf(
            "steaks", "feedviewer", "notes", "bookmarks", "audio", "voice", "geohash",
            "calendar", "wallet", "updates", "followlists",
        )

    fun setToolOrder(order: List<String>) {
        viewModelScope.launch { appSettings.setToolboxToolOrder(order) }
    }

}
