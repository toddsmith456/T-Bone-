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
import javax.inject.Inject

@HiltViewModel
class GlobalRelayViewModel @Inject constructor(
    private val pool: RelayPool,
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
        viewModelScope.launch {
            appSettings.addGlobalRelay(normalized)
            pool.addRelay(normalized)
        }
        return true
    }

    fun removeRelay(url: String) {
        val normalized = RelayPool.normalize(url)
        viewModelScope.launch {
            // If this relay is not in personal list, disconnect it
            val wasActive = normalized in appSettings.globalActiveRelays.value
            appSettings.removeGlobalRelay(normalized)
            // Only remove from pool if it's not needed for personal relays
            // But we don't have easy access to personal relays here; we check if pool has it and it's not in active global set after removal.
            // If personal list still needs it, it will be re-added by FeedViewModel loadFeed.
            // For simplicity, remove from pool if no longer in global list.
            // Personal relays will be re-added when home feed loads.
            if (wasActive) {
                // Don't immediately remove if personal relays contain it? We can't know here, so keep pool connection.
                // Instead we just leave it — no harm. Or we could query activeAccount but that's in another VM.
                // Safer to keep pool connection; it will be reused.
            }
        }
    }

    fun toggleActive(url: String) {
        viewModelScope.launch {
            appSettings.toggleGlobalRelayActive(url)
            // Ensure newly enabled relay is connected
            val normalized = RelayPool.normalize(url)
            if (normalized in appSettings.globalActiveRelays.value) {
                pool.addRelay(normalized)
            }
        }
    }

    fun solo(url: String) {
        viewModelScope.launch {
            appSettings.soloGlobalRelay(url)
            pool.addRelay(RelayPool.normalize(url))
        }
    }

    fun enableAll() {
        viewModelScope.launch { appSettings.enableAllGlobalRelays() }
        // Connect all
        globalRelays.value.forEach { pool.addRelay(it) }
    }

    fun reset() {
        viewModelScope.launch { appSettings.resetGlobalRelays() }
        AppSettings.GLOBAL_RELAY_DEFAULTS.forEach { pool.addRelay(it) }
    }
}
