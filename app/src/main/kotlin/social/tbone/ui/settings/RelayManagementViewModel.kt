package social.tbone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.AccountRepository
import social.tbone.lists.PublishedRelayLists
import social.tbone.lists.RelayListFetcher
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import javax.inject.Inject

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val relayListFetcher: RelayListFetcher,
) : ViewModel() {

    val relayStatuses: StateFlow<Map<String, RelayStatus>> = pool.relayStatuses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Pubkey never changes for the active account; Eagerly so .value is always ready.
    private val activePubkey: StateFlow<String?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _syncState = MutableStateFlow(PublishedListSyncState())
    val syncState: StateFlow<PublishedListSyncState> = _syncState.asStateFlow()

    /** Fetches kind 10002 and applies its readable preferred relays to this account. */
    fun syncPreferredRelays() {
        if (_syncState.value.syncing) return
        viewModelScope.launch {
            _syncState.value = PublishedListSyncState(syncing = true, message = "syncing published list…")
            try {
                val account = accountRepository.activeAccount.first()
                    ?: error("Add an account before syncing preferred relays.")
                val result = relayListFetcher.fetchLatest(
                    pubkey = account.pubkey,
                    kind = EventKind.RELAY_LIST,
                    seedRelays = account.relays,
                )
                val event = result.event ?: run {
                    _syncState.value = PublishedListSyncState(
                        message = if (result.reachedRelays) "No published relay list was found."
                        else "Could not reach your relays. Try again.",
                        isError = true,
                    )
                    return@launch
                }
                val imported = PublishedRelayLists.preferredRelays(event)
                if (imported.isEmpty()) {
                    _syncState.value = PublishedListSyncState(
                        message = "The published relay list has no valid readable relays; current relays were kept.",
                        isError = true,
                    )
                    return@launch
                }
                accountRepository.updateAccount(account.pubkey) { it.copy(relays = imported) }
                val previous = pool.relayUrls()
                imported.forEach(pool::addRelay)
                (previous - imported.toSet()).forEach(pool::removeRelay)
                _syncState.value = PublishedListSyncState(
                    message = "Synced ${imported.size} preferred relay${if (imported.size == 1) "" else "s"} from your published list.",
                )
            } catch (error: Exception) {
                _syncState.value = PublishedListSyncState(
                    message = error.message ?: "Could not sync your published relay list.",
                    isError = true,
                )
            }
        }
    }

    fun addRelay(url: String): Boolean {
        val normalized = RelayPool.normalize(url)
        if (!RelayPool.isValidRelayUrl(normalized)) return false
        pool.addRelay(normalized)
        persistRelays { relays -> (relays + normalized).distinct() }
        return true
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
