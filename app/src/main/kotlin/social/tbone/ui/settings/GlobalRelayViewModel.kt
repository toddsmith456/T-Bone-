package social.tbone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import social.tbone.settings.AppSettings
import social.tbone.di.GlobalFeedPool
import javax.inject.Inject

@HiltViewModel
class GlobalRelayViewModel @Inject constructor(
    @GlobalFeedPool private val pool: RelayPool,
    private val appSettings: AppSettings,
) : ViewModel() {

    val globalRelays: StateFlow<Set<String>> = appSettings.globalRelays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.GLOBAL_RELAY_DEFAULTS)

    val globalActiveRelays: StateFlow<Set<String>> = appSettings.globalActiveRelays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.GLOBAL_RELAY_DEFAULTS)

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addRelay(url: String): Boolean {
        val normalized = RelayPool.normalize(url)
        if (!RelayPool.isValidRelayUrl(normalized)) return false
        viewModelScope.launch { appSettings.addGlobalRelay(normalized) }
        return true
    }

    fun removeRelay(url: String) { viewModelScope.launch { appSettings.removeGlobalRelay(url) } }
    fun toggleActive(url: String) { viewModelScope.launch { appSettings.toggleGlobalRelayActive(url) } }
    fun solo(url: String) { viewModelScope.launch { appSettings.soloGlobalRelay(url) } }
    fun enableAll() { viewModelScope.launch { appSettings.enableAllGlobalRelays() } }
    fun reset() { viewModelScope.launch { appSettings.resetGlobalRelays() } }
}
