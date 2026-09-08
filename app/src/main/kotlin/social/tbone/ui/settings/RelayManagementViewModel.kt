package social.tbone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import javax.inject.Inject

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
) : ViewModel() {

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Pubkey never changes for the active account; Eagerly so .value is always ready.
    private val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun addRelay(url: String) {
        val normalized = RelayPool.normalize(url.trim())
        if (normalized.isBlank()) return
        pool.addRelay(normalized)
        persistRelays { relays -> (relays + normalized).distinct() }
    }

    fun removeRelay(url: String) {
        val normalized = RelayPool.normalize(url)
        pool.removeRelay(normalized)
        persistRelays { relays -> relays.map { RelayPool.normalize(it) }.filter { it != normalized }.distinct() }
    }

    private fun persistRelays(transform: (List<String>) -> List<String>) {
        val pubkey = activePubkey.value ?: return
        viewModelScope.launch {
            accountRepository.updateAccount(pubkey) { it.copy(relays = transform(it.relays)) }
        }
    }
}
