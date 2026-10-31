package social.tbone.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.account.AccountRepository
import social.tbone.lists.PublishedRelayLists
import social.tbone.lists.RelayFileFormat
import social.tbone.lists.RelayListFetcher
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.RelayPool
import social.tbone.nostr.relay.RelayStatus
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

sealed interface RelayImportDialog {
    data class ImportChoice(
        val parsed: RelayFileFormat.Parsed,
        val currentCount: Int,
        val accountPubkey: String,
        val previousRelays: List<String>,
        val previouslyConfigured: Boolean,
    ) : RelayImportDialog
}

data class RelayManagementUi(
    val busy: Boolean = false,
    val busyLabel: String = "",
    val importDialog: RelayImportDialog? = null,
)

@HiltViewModel
class RelayManagementViewModel @Inject constructor(
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
    private val relayListFetcher: RelayListFetcher,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Show the saved personal list, never the shared pool's temporary connections.
    val relayStatuses: StateFlow<Map<String, RelayStatus>> = combine(accountRepository.activeAccount, pool.relayStatuses) { account, statuses ->
        account?.effectiveRelays().orEmpty().map(RelayPool::normalize).distinct()
            .associateWith { statuses[it] ?: RelayStatus.DISCONNECTED }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val activeAccount = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _syncState = MutableStateFlow(PublishedListSyncState())
    val syncState: StateFlow<PublishedListSyncState> = _syncState.asStateFlow()

    private val _ui = MutableStateFlow(RelayManagementUi())
    val ui: StateFlow<RelayManagementUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private fun say(text: String) { _messages.tryEmit(text) }
    private fun busy(label: String) { _ui.value = _ui.value.copy(busy = true, busyLabel = label) }
    private fun idle() { _ui.value = _ui.value.copy(busy = false, busyLabel = "") }
    fun dismissImportDialog() { _ui.value = _ui.value.copy(importDialog = null) }

    /**
     * Fetches kind 10002 and applies the ENTIRE relay list to this account.
     * Uses [PublishedRelayLists.allRelays] (all markers) — not just read relays —
     * so users get their full list, exactly as Amethyst does.
     */
    fun syncPreferredRelays() {
        if (_syncState.value.syncing) return
        viewModelScope.launch {
            _syncState.value = PublishedListSyncState(syncing = true, message = "syncing published list…")
            try {
                val account = accountRepository.activeAccount.first()
                    ?: error("Add an account before syncing relays.")
                val result = relayListFetcher.fetchLatest(
                    pubkey = account.pubkey,
                    kind = EventKind.RELAY_LIST,
                    seedRelays = account.effectiveRelays(),
                )
                val event = result.event ?: run {
                    _syncState.value = PublishedListSyncState(
                        message = if (result.reachedRelays) "No published relay list was found."
                        else "Could not reach your relays. Try again.",
                        isError = true,
                    )
                    return@launch
                }
                // Use the full list, not just readable — fixes "only 5 relays" bug.
                val imported = PublishedRelayLists.allRelays(event)
                if (imported.isEmpty()) {
                    _syncState.value = PublishedListSyncState(
                        message = "The published relay list has no valid relays; current relays were kept.",
                        isError = true,
                    )
                    return@launch
                }
                // Do not overwrite edits made while the network request was in flight.
                var applied = false
                accountRepository.updateAccount(account.pubkey) { current ->
                    if (current.relays == account.relays && current.relayListConfigured == account.relayListConfigured) {
                        applied = true
                        current.copy(relays = imported, relayListConfigured = true)
                    } else current
                }
                if (!applied) error("Relay list changed while syncing; your edits were kept. Try again.")
                _syncState.value = PublishedListSyncState(
                    message = "Synced ${imported.size} relay${if (imported.size == 1) "" else "s"} from your published list.",
                )
                say("Synced ${imported.size} relays from your published list.")
            } catch (error: Exception) {
                Timber.w(error, "Relay sync failed")
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
        // Persist atomically — fetch pubkey at call time, not from stale StateFlow
        viewModelScope.launch {
            val account = accountRepository.activeAccount.first() ?: return@launch
            accountRepository.updateAccount(account.pubkey) { current ->
                val normalizedExisting = current.effectiveRelays().map { RelayPool.normalize(it) }
                if (normalized in normalizedExisting) current
                else current.copy(relays = (normalizedExisting + normalized).distinct(), relayListConfigured = true)
            }
        }
        return true
    }

    fun removeRelay(url: String) {
        val normalized = RelayPool.normalize(url)
        viewModelScope.launch {
            val account = accountRepository.activeAccount.first() ?: return@launch
            accountRepository.updateAccount(account.pubkey) { current ->
                val filtered = current.effectiveRelays().map { RelayPool.normalize(it) }.filter { it != normalized }
                // Keep distinct normalized URLs but preserve original casing where possible?
                // We store normalized anyway.
                current.copy(relays = filtered.distinct(), relayListConfigured = true)
            }
        }
    }

    // ── Import / Export ─────────────────────────────────────────────────────

    fun exportFileName(): String {
        val pk = activeAccount.value?.pubkey ?: "account"
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return RelayFileFormat.suggestedFileName(pk, date)
    }

    fun exportTo(uri: Uri) {
        viewModelScope.launch {
            busy("exporting…")
            try {
                val account = accountRepository.activeAccount.first()
                    ?: error("No active account")
                val relays = account.effectiveRelays()
                val text = RelayFileFormat.export(account.pubkey, relays)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("could not open output stream")
                }
                say("Exported ${relays.size} relays to file.")
                _syncState.value = PublishedListSyncState(message = "Exported ${relays.size} relays.")
            } catch (t: Throwable) {
                Timber.w(t, "Relay export failed")
                say("Export failed: ${t.message ?: "unknown error"}")
                _syncState.value = PublishedListSyncState(message = "Export failed: ${t.message}", isError = true)
            } finally {
                idle()
            }
        }
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            busy("reading file…")
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val limit = RelayFileFormat.MAX_FILE_CHARS
                        val reader = stream.bufferedReader(Charsets.UTF_8)
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            sb.append(buf, 0, n)
                            if (sb.length > limit) break
                        }
                        sb.toString()
                    } ?: error("could not open file")
                }
                val parsed = withContext(Dispatchers.Default) { RelayFileFormat.parse(text) }
                if (parsed.error != null) {
                    say(parsed.error)
                    _syncState.value = PublishedListSyncState(message = parsed.error, isError = true)
                } else {
                    val account = accountRepository.activeAccount.first() ?: error("No active account")
                    _ui.value = _ui.value.copy(
                        importDialog = RelayImportDialog.ImportChoice(
                            parsed, account.effectiveRelays().size, account.pubkey,
                            account.relays, account.relayListConfigured,
                        )
                    )
                }
            } catch (t: Throwable) {
                Timber.w(t, "Relay import failed")
                say("Import failed: ${t.message ?: "unknown error"}")
            } finally {
                idle()
            }
        }
    }

    fun applyImport(replace: Boolean) {
        val dialog = _ui.value.importDialog as? RelayImportDialog.ImportChoice ?: return
        dismissImportDialog()
        viewModelScope.launch {
            val account = accountRepository.activeAccount.first() ?: return@launch
            if (account.pubkey != dialog.accountPubkey) {
                say("Account changed; import canceled. Start the import again.")
                return@launch
            }
            val incoming = dialog.parsed.relays
            val skippedMsg = if (dialog.parsed.skipped > 0) " (${dialog.parsed.skipped} skipped)" else ""
            if (replace) {
                var applied = false
                accountRepository.updateAccount(account.pubkey) { current ->
                    if (current.relays == dialog.previousRelays && current.relayListConfigured == dialog.previouslyConfigured) {
                        applied = true
                        current.copy(relays = incoming, relayListConfigured = true)
                    } else current
                }
                if (!applied) {
                    say("Relay list changed; import canceled. Start the import again.")
                    return@launch
                }
                say("Imported — relay list replaced with ${incoming.size} relays$skippedMsg.")
                _syncState.value = PublishedListSyncState(message = "Imported — replaced with ${incoming.size} relays$skippedMsg.")
            } else {
                // Merge: add only new ones
                val toAdd = incoming.filterNot { it in account.effectiveRelays().map { r -> RelayPool.normalize(r) } }
                if (toAdd.isEmpty()) {
                    say("Import — no new relays to add$skippedMsg.")
                    _syncState.value = PublishedListSyncState(message = "Import — no new relays to add$skippedMsg.")
                    return@launch
                }
                accountRepository.updateAccount(account.pubkey) { cur ->
                    cur.copy(relays = (cur.effectiveRelays() + toAdd).distinct(), relayListConfigured = true)
                }
                say("Imported — ${toAdd.size} new relay${if (toAdd.size == 1) "" else "s"} added$skippedMsg.")
                _syncState.value = PublishedListSyncState(message = "Imported — ${toAdd.size} new relays added$skippedMsg.")
            }
        }
    }
}
