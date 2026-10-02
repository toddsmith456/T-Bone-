package social.tbone.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.account.FollowEntry
import social.tbone.lists.AddResult
import social.tbone.lists.ListEntry
import social.tbone.lists.ListFileFormat
import social.tbone.lists.ListType
import social.tbone.lists.LocalListState
import social.tbone.lists.MuteListRepository
import social.tbone.lists.OfflineListRepository
import social.tbone.lists.OnlineFetch
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Modal dialogs the offline-list screen can show. */
sealed interface OfflineListDialog {
    /** Switching ON for the first time couldn't reach the relays. */
    data class InitialSyncFailed(val cachedCopy: List<ListEntry>) : OfflineListDialog

    /** "SYNC ONLINE → OFFLINE" warning (it replaces the local list). */
    data class ConfirmSync(val localCount: Int) : OfflineListDialog

    /** A parsed import file waiting for MERGE / REPLACE. */
    data class ImportChoice(
        val parsed: ListFileFormat.Parsed,
        val fromOtherAccount: Boolean,
        val currentCount: Int,
    ) : OfflineListDialog
}

data class OfflineListUi(
    val busy: Boolean = false,
    val busyLabel: String = "",
    val dialog: OfflineListDialog? = null,
)

/** One-shot message for the snackbar, optionally with an UNDO payload. */
data class OfflineListMessage(val text: String, val undo: ListEntry? = null)

/**
 * Drives the "offline follow list" / "offline block list" screen. The same
 * ViewModel serves both lists — the route argument `type` picks which.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OfflineListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val offline: OfflineListRepository,
    private val muteRepository: MuteListRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val type: ListType = ListType.fromSlug(savedStateHandle["type"]) ?: ListType.FOLLOWS

    val account: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Local list of the ACTIVE account (null while loading / when there is no account). */
    val state: StateFlow<LocalListState?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .flatMapLatest { pk -> if (pk == null) flowOf(null) else offline.state(type, pk) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Cached names only — the browse list never fetches or caches pictures. */
    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _ui = MutableStateFlow(OfflineListUi())
    val ui: StateFlow<OfflineListUi> = _ui.asStateFlow()

    private val _messages = MutableSharedFlow<OfflineListMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<OfflineListMessage> = _messages.asSharedFlow()

    private fun say(text: String, undo: ListEntry? = null) {
        _messages.tryEmit(OfflineListMessage(text, undo))
    }

    private fun busy(label: String) = _ui.update { it.copy(busy = true, busyLabel = label) }
    private fun idle() = _ui.update { it.copy(busy = false, busyLabel = "") }
    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    // ── The switch ────────────────────────────────────────────────────────────

    fun onSwitch(wantOn: Boolean) {
        val acct = account.value ?: return
        if (_ui.value.busy) return
        viewModelScope.launch {
            val cur = offline.snapshot(type, acct.pubkey)
            if (!wantOn) {
                offline.setEnabled(type, acct.pubkey, false)
                say("Offline ${type.noun} list OFF — using your relay list again.")
                return@launch
            }
            if (cur.initialSyncDone) {
                offline.setEnabled(type, acct.pubkey, true)
                say("Offline ${type.noun} list ON — using ${cur.size} local ${type.plural}.")
                return@launch
            }
            runInitialSync(acct)
        }
    }

    /** The one and only automatic sync: the first time the switch is turned on. */
    private suspend fun runInitialSync(acct: Account) {
        busy("syncing from your relays…")
        try {
            when (val r = offline.fetchOnline(type, acct.pubkey, acct.relays)) {
                is OnlineFetch.Found -> {
                    val entries = withLegacy(r.entries)
                    offline.replaceFromSync(type, acct.pubkey, entries, enable = true)
                    say("Synced ${entries.size} ${type.plural} from your relays. Offline list is ON.")
                }
                OnlineFetch.Empty -> {
                    val entries = withLegacy(emptyList())
                    offline.replaceFromSync(type, acct.pubkey, entries, enable = true)
                    say("No ${type.listName} found on your relays — starting with ${entries.size}. Offline list is ON.")
                }
                OnlineFetch.Unreachable -> {
                    _ui.update { it.copy(dialog = OfflineListDialog.InitialSyncFailed(cachedCopy(acct))) }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "Initial offline sync failed")
            _ui.update { it.copy(dialog = OfflineListDialog.InitialSyncFailed(cachedCopy(acct))) }
        } finally {
            idle()
        }
    }

    /** Retry from the "couldn't reach relays" dialog. */
    fun retryInitialSync() {
        val acct = account.value ?: return
        dismissDialog()
        viewModelScope.launch { runInitialSync(acct) }
    }

    /** From the failure dialog: enable using a local copy (or empty) without any relay access. */
    fun enableWithoutSync(copy: List<ListEntry>) {
        val acct = account.value ?: return
        dismissDialog()
        viewModelScope.launch {
            offline.replaceFromSync(type, acct.pubkey, copy, enable = true, stampSynced = false)
            say("Offline ${type.noun} list ON with ${copy.size} ${type.plural}. Use SYNC any time to pull from your relays.")
        }
    }

    // ── Manual sync (online → offline only) ───────────────────────────────────

    fun requestSync() {
        val st = state.value ?: return
        if (_ui.value.busy) return
        _ui.update { it.copy(dialog = OfflineListDialog.ConfirmSync(st.size)) }
    }

    fun confirmSync() {
        val acct = account.value ?: return
        dismissDialog()
        viewModelScope.launch {
            busy("syncing from your relays…")
            try {
                when (val r = offline.fetchOnline(type, acct.pubkey, acct.relays)) {
                    is OnlineFetch.Found -> {
                        offline.replaceFromSync(type, acct.pubkey, r.entries, enable = false)
                        say("Synced: offline list replaced with ${r.entries.size} ${type.plural} from your relays.")
                    }
                    // A relay that merely has no copy must never wipe a local list.
                    OnlineFetch.Empty ->
                        say("No ${type.listName} found on your relays — offline list left unchanged.")
                    OnlineFetch.Unreachable ->
                        say("Couldn't reach any relay — offline list left unchanged.")
                }
            } catch (t: Throwable) {
                Timber.w(t, "Manual offline sync failed")
                say("Sync failed — offline list left unchanged.")
            } finally {
                idle()
            }
        }
    }

    // ── Add / remove by npub ──────────────────────────────────────────────────

    /** Returns true when the input was accepted (so the UI can clear the text field). */
    fun addByInput(input: String, onDone: (Boolean) -> Unit) {
        val acct = account.value ?: return onDone(false)
        viewModelScope.launch {
            val r = offline.addByInput(type, acct.pubkey, input)
            when (r) {
                AddResult.ADDED -> say("Added to your offline ${type.noun} list.")
                AddResult.ALREADY_PRESENT -> say("Already in the list.")
                AddResult.INVALID -> say("That isn't a valid npub / hex pubkey.")
                AddResult.SELF -> say("You can't add your own account.")
                AddResult.LIST_FULL -> say("The list is full (${ListFileFormat.MAX_ENTRIES} max).")
            }
            onDone(r == AddResult.ADDED)
        }
    }

    fun remove(pubkey: String) {
        val acct = account.value ?: return
        viewModelScope.launch {
            val removed = offline.remove(type, acct.pubkey, pubkey)
            if (removed != null) say("Removed from your offline ${type.noun} list.", undo = removed)
        }
    }

    fun undoRemove(entry: ListEntry) {
        val acct = account.value ?: return
        viewModelScope.launch { offline.restore(type, acct.pubkey, entry) }
    }

    // ── Export / import (single file) ─────────────────────────────────────────

    fun exportFileName(): String {
        val pk = account.value?.pubkey ?: "account"
        val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        return ListFileFormat.suggestedFileName(type, pk, date)
    }

    fun exportTo(uri: Uri) {
        val acct = account.value ?: return
        viewModelScope.launch {
            busy("exporting…")
            try {
                val st = offline.snapshot(type, acct.pubkey)
                val text = ListFileFormat.export(type, acct.pubkey, st.entries)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("could not open output stream")
                }
                say("Exported ${st.size} ${type.plural} to one file.")
            } catch (t: Throwable) {
                Timber.w(t, "Export failed")
                say("Export failed: ${t.message ?: "unknown error"}")
            } finally {
                idle()
            }
        }
    }

    fun importFrom(uri: Uri) {
        val acct = account.value ?: return
        viewModelScope.launch {
            busy("reading file…")
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // Bounded read: a hostile/huge file can't exhaust memory.
                        val limit = ListFileFormat.MAX_FILE_CHARS
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
                val parsed = withContext(Dispatchers.Default) { ListFileFormat.parse(text, type) }
                if (parsed.error != null) {
                    say(parsed.error)
                } else {
                    val cur = offline.snapshot(type, acct.pubkey)
                    _ui.update {
                        it.copy(
                            dialog = OfflineListDialog.ImportChoice(
                                parsed = parsed,
                                fromOtherAccount = parsed.ownerPubkey != null && parsed.ownerPubkey != acct.pubkey,
                                currentCount = cur.size,
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "Import failed")
                say("Import failed: ${t.message ?: "unknown error"}")
            } finally {
                idle()
            }
        }
    }

    fun applyImport(replace: Boolean) {
        val acct = account.value ?: return
        val dialog = _ui.value.dialog as? OfflineListDialog.ImportChoice ?: return
        dismissDialog()
        viewModelScope.launch {
            val skipped = if (dialog.parsed.skipped > 0) " (${dialog.parsed.skipped} unreadable items skipped)" else ""
            if (replace) {
                val n = offline.replaceImport(type, acct.pubkey, dialog.parsed.entries)
                say("Imported — offline list replaced with $n ${type.plural}$skipped.")
            } else {
                val added = offline.mergeImport(type, acct.pubkey, dialog.parsed.entries)
                say("Imported — $added new ${type.plural} added$skipped.")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Block list only: device-local blocks from before this feature are carried over once. */
    private fun withLegacy(online: List<ListEntry>): List<ListEntry> {
        if (type != ListType.MUTES) return online
        val have = online.mapTo(HashSet()) { it.pubkey }
        return online + muteRepository.legacyBlocked().filter { it !in have }.map { ListEntry(it) }
    }

    /** Best local copy to offer when the relays can't be reached. */
    private suspend fun cachedCopy(acct: Account): List<ListEntry> {
        val copy: List<ListEntry> = when (type) {
            ListType.FOLLOWS -> {
                val src = acct.followEntries.ifEmpty { acct.follows.map { FollowEntry(it) } }
                src.map { ListEntry(it.pubkey, it.relay, it.petname) }
            }
            ListType.MUTES -> withLegacy(muteRepository.cachedRelayMuted(acct.pubkey).map { ListEntry(it) })
        }
        return copy.filter { it.pubkey != acct.pubkey }.distinctBy { it.pubkey }
    }
}
