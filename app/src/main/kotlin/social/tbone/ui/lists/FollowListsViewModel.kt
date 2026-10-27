package social.tbone.ui.lists

import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.flow.combine
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
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.lists.AddResult
import social.tbone.lists.FollowerListRepository
import social.tbone.lists.ListEntry
import social.tbone.lists.ListFileFormat
import social.tbone.lists.ListType
import social.tbone.lists.LocalListState
import social.tbone.lists.MuteListRepository
import social.tbone.lists.MuteOutcome
import social.tbone.lists.OfflineListRepository
import social.tbone.lists.OnlineFetch
import social.tbone.nostr.EventKind
import social.tbone.nostr.NostrJson
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import timber.log.Timber
import javax.inject.Inject

sealed interface FollowListsMessage {
    data class Text(val text: String, val undo: ListEntry? = null) : FollowListsMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FollowListsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountRepository: AccountRepository,
    private val offline: OfflineListRepository,
    private val muteRepository: MuteListRepository,
    private val followerRepo: FollowerListRepository,
    private val signerFactory: NostrSignerFactory,
    private val listFetcher: social.tbone.lists.RelayListFetcher,
    private val relayPool: social.tbone.nostr.relay.RelayPool,
    private val appSettings: social.tbone.settings.AppSettings,
    profileRepository: ProfileRepository,
) : ViewModel() {

    val account: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Offline states ────────────────────────────────────────────────────────
    val followsOfflineState: StateFlow<LocalListState?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .flatMapLatest { pk -> if (pk == null) flowOf(null) else offline.state(ListType.FOLLOWS, pk) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mutesOfflineState: StateFlow<LocalListState?> = accountRepository.activeAccount
        .map { it?.pubkey }
        .flatMapLatest { pk -> if (pk == null) flowOf(null) else offline.state(ListType.MUTES, pk) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Online following (relay) ────────────────────────────────────────────
    private val _onlineFollowing = MutableStateFlow<List<ListEntry>>(emptyList())
    val onlineFollowing: StateFlow<List<ListEntry>> = _onlineFollowing.asStateFlow()

    private val _onlineLoading = MutableStateFlow(false)
    val onlineLoading: StateFlow<Boolean> = _onlineLoading.asStateFlow()

    private val _onlineError = MutableStateFlow<String?>(null)
    val onlineError: StateFlow<String?> = _onlineError.asStateFlow()

    // Displayed following depends on mode: if offline enabled → offline.entries else online
    val displayedFollowing: StateFlow<List<ListEntry>> = combine(
        followsOfflineState, _onlineFollowing
    ) { offlineState, online ->
        if (offlineState?.enabled == true) offlineState.entries else online
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Blocked / muted effective ───────────────────────────────────────────
    val effectiveMuted: StateFlow<Set<String>> = muteRepository.effectiveMuted
    val offlineMutesEnabled: StateFlow<Boolean> = muteRepository.offlineEnabled

    // ── Followers (centralized via Primal) ─────────────────────────────────
    val followersEnabled: StateFlow<Boolean> = appSettings.followersEnabled
    val followersDisclaimerShown: StateFlow<Boolean> = appSettings.followersDisclaimerShown

    private val _followers = MutableStateFlow<FollowerListRepository.FollowersState?>(null)
    val followers: StateFlow<FollowerListRepository.FollowersState?> = _followers.asStateFlow()

    private val _followersBusy = MutableStateFlow(false)
    val followersBusy: StateFlow<Boolean> = _followersBusy.asStateFlow()

    private val _messages = MutableSharedFlow<FollowListsMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<FollowListsMessage> = _messages.asSharedFlow()

    private fun say(text: String, undo: ListEntry? = null) {
        _messages.tryEmit(FollowListsMessage.Text(text, undo))
    }

    // ── Init: load online following for active account ──────────────────────
    init {
        viewModelScope.launch {
            accountRepository.activeAccount.collect { acct ->
                if (acct == null) {
                    _onlineFollowing.value = emptyList()
                } else {
                    // Seed from cached account data immediately
                    val cached = acct.followEntries.map { ListEntry(it.pubkey, it.relay, it.petname) }
                        .ifEmpty { acct.follows.map { ListEntry(it) } }
                    _onlineFollowing.value = cached
                    refreshOnlineFollowing(acct.pubkey, acct.relays)
                }
            }
        }
        // Followers auto-load when enabled
        viewModelScope.launch {
            combine(account, followersEnabled) { a, e -> a to e }.collect { (acct, enabled) ->
                if (acct != null && enabled) {
                    loadFollowers(acct.pubkey, acct.relays)
                } else {
                    _followers.value = null
                }
            }
        }
    }

    fun refreshOnlineFollowing(pubkey: String? = account.value?.pubkey, relays: List<String> = account.value?.relays.orEmpty()) {
        val pk = pubkey ?: return
        viewModelScope.launch {
            _onlineLoading.value = true
            _onlineError.value = null
            try {
                when (val r = offline.fetchOnline(ListType.FOLLOWS, pk, relays)) {
                    is OnlineFetch.Found -> {
                        _onlineFollowing.value = r.entries
                        // Also keep account cached in sync (optional)
                        // Update account's follow list cache locally for quick profile checks
                        accountRepository.updateAccount(pk) { acct ->
                            acct.copy(
                                follows = r.entries.map { it.pubkey },
                                followEntries = r.entries.map { FollowEntry(it.pubkey, it.relay, it.petname) }
                            )
                        }
                    }
                    OnlineFetch.Empty -> {
                        // No relay has list — keep cached (or empty if truly empty)
                        if (_onlineFollowing.value.isEmpty()) _onlineFollowing.value = emptyList()
                    }
                    OnlineFetch.Unreachable -> {
                        _onlineError.value = "Couldn't reach relays"
                    }
                }
            } catch (e: Exception) {
                _onlineError.value = e.message ?: "Failed to load"
            } finally {
                _onlineLoading.value = false
            }
        }
    }

    // ── Follow / Unfollow ───────────────────────────────────────────────────
    // Works in both modes: offline → local list, online → publish to relays
    fun follow(pubkeyHex: String) {
        val acct = account.value ?: return
        if (followsOfflineState.value?.enabled == true) {
            viewModelScope.launch {
                val res = offline.add(ListType.FOLLOWS, acct.pubkey, ListEntry(pubkeyHex))
                when (res) {
                    AddResult.ADDED -> say("Added to offline follow list")
                    AddResult.ALREADY_PRESENT -> say("Already following")
                    else -> say("Couldn't add")
                }
            }
        } else {
            doFollowOnline(pubkeyHex)
        }
    }

    // We need RelayPool injection — add lazily via field injection later
    fun unfollow(pubkeyHex: String) {
        val acct = account.value ?: return
        viewModelScope.launch {
            val isOffline = followsOfflineState.value?.enabled == true
            if (isOffline) {
                val removed = offline.remove(ListType.FOLLOWS, acct.pubkey, pubkeyHex)
                if (removed != null) say("Removed from offline follow list", undo = removed) else say("Not in list")
            } else {
                _onlineLoading.value = true
                try {
                    val signer = signerFactory.forActiveAccount() ?: run { say("No signer"); return@launch }
                    val latest = listFetcher.fetchLatest(acct.pubkey, EventKind.FOLLOW_LIST, acct.relays).event
                    val existing = latest?.let { OfflineListRepository.entriesFromEvent(it, acct.pubkey).map { FollowEntry(it.pubkey, it.relay, it.petname) } }
                        ?: acct.followEntries.ifEmpty { acct.follows.map { FollowEntry(it) } }
                    if (existing.none { it.pubkey == pubkeyHex }) { say("Not following"); return@launch }
                    val newEntries = existing.filter { it.pubkey != pubkeyHex }
                    val keptTags = latest?.tags?.filter { (it.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content != "p" }.orEmpty()
                    val pTags = newEntries.map { e ->
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(e.pubkey))
                            if (e.relay.isNotEmpty() || e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.relay))
                            if (e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.petname))
                        }
                    }
                    val unsigned = social.tbone.nostr.UnsignedEvent(
                        pubkey = signer.pubkey,
                        createdAt = maxOf(System.currentTimeMillis()/1000, (latest?.createdAt ?: 0L) + 1),
                        kind = EventKind.FOLLOW_LIST,
                        content = latest?.content ?: "",
                        tags = pTags + keptTags,
                    )
                    signer.signEvent(unsigned).onSuccess { ev ->
                        relayPool.publish(ev)
                        accountRepository.updateAccount(acct.pubkey) {
                            it.copy(follows = newEntries.map { e -> e.pubkey }, followEntries = newEntries)
                        }
                        _onlineFollowing.value = newEntries.map { ListEntry(it.pubkey, it.relay, it.petname) }
                        say("Unfollowed")
                    }.onFailure { say("Signing failed") }
                } finally { _onlineLoading.value = false }
            }
        }
    }

    fun toggleMute(pubkeyHex: String) {
        viewModelScope.launch {
            val isBlocked = pubkeyHex in effectiveMuted.value
            val outcome = muteRepository.setMuted(pubkeyHex, !isBlocked)
            when (outcome) {
                MuteOutcome.SAVED_LOCAL -> say(if (!isBlocked) "Blocked (offline)" else "Unblocked (offline)")
                MuteOutcome.PUBLISHED -> say(if (!isBlocked) "Blocked" else "Unblocked")
                MuteOutcome.UNCHANGED -> say("Already ${if (isBlocked) "blocked" else "unblocked"}")
                MuteOutcome.RELAYS_UNREACHABLE -> say("Couldn't reach relays — not changed")
                MuteOutcome.NO_SIGNER -> say("No signer — not changed")
                else -> say("Couldn't change block")
            }
        }
    }

    // For publish we also need to handle follow() properly with relayPool.publish
    fun doFollowOnline(pubkeyHex: String) {
        val acct = account.value ?: return
        viewModelScope.launch {
            _onlineLoading.value = true
            try {
                val signer = signerFactory.forActiveAccount() ?: run { say("No signer"); return@launch }
                val latest = listFetcher.fetchLatest(acct.pubkey, EventKind.FOLLOW_LIST, acct.relays).event
                val existing = latest?.let { OfflineListRepository.entriesFromEvent(it, acct.pubkey).map { FollowEntry(it.pubkey, it.relay, it.petname) } }
                    ?: acct.followEntries.ifEmpty { acct.follows.map { FollowEntry(it) } }
                if (existing.any { it.pubkey == pubkeyHex }) { say("Already following"); return@launch }
                val newEntries = (existing + FollowEntry(pubkeyHex)).distinctBy { it.pubkey }
                val keptTags = latest?.tags?.filter { (it.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content != "p" }.orEmpty()
                val pTags = newEntries.map { e ->
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(e.pubkey))
                        if (e.relay.isNotEmpty() || e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.relay))
                        if (e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.petname))
                    }
                }
                val unsigned = social.tbone.nostr.UnsignedEvent(
                    pubkey = signer.pubkey,
                    createdAt = maxOf(System.currentTimeMillis()/1000, (latest?.createdAt ?: 0L) + 1),
                    kind = EventKind.FOLLOW_LIST,
                    content = latest?.content ?: "",
                    tags = pTags + keptTags,
                )
                signer.signEvent(unsigned).onSuccess { ev ->
                    relayPool.publish(ev)
                    accountRepository.updateAccount(acct.pubkey) {
                        it.copy(follows = newEntries.map { it.pubkey }, followEntries = newEntries)
                    }
                    _onlineFollowing.value = newEntries.map { ListEntry(it.pubkey, it.relay, it.petname) }
                    say("Followed")
                }.onFailure { say("Signing failed: ${it.message}") }
            } finally { _onlineLoading.value = false }
        }
    }

    // ── Offline switch ──────────────────────────────────────────────────────
    fun onSwitchOfflineWant(on: Boolean) {
        val acct = account.value ?: return
        viewModelScope.launch {
            val cur = offline.snapshot(ListType.FOLLOWS, acct.pubkey)
            if (!on) {
                offline.setEnabled(ListType.FOLLOWS, acct.pubkey, false)
                say("Offline follow list OFF — using relay list")
                refreshOnlineFollowing(acct.pubkey, acct.relays)
                return@launch
            }
            if (cur.initialSyncDone) {
                offline.setEnabled(ListType.FOLLOWS, acct.pubkey, true)
                say("Offline follow list ON — using ${cur.size} local follows")
                return@launch
            }
            // Initial sync
            _onlineLoading.value = true
            try {
                when (val r = offline.fetchOnline(ListType.FOLLOWS, acct.pubkey, acct.relays)) {
                    is OnlineFetch.Found -> {
                        offline.replaceFromSync(ListType.FOLLOWS, acct.pubkey, r.entries, enable = true)
                        say("Synced ${r.entries.size} follows from relays. Offline list ON.")
                    }
                    OnlineFetch.Empty -> {
                        offline.replaceFromSync(ListType.FOLLOWS, acct.pubkey, emptyList(), enable = true)
                        say("No follow list on relays — starting empty. Offline list ON.")
                    }
                    OnlineFetch.Unreachable -> {
                        say("Couldn't reach relays — offline list not enabled")
                    }
                }
            } finally { _onlineLoading.value = false }
        }
    }

    // ── Followers ───────────────────────────────────────────────────────────
    fun setFollowersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !followersDisclaimerShown.value) {
                // Caller should show popup before calling this; we handle fallback here: show and require confirmation
                // For now just set and let UI show dialog
            }
            appSettings.setFollowersEnabled(enabled)
            if (enabled) {
                account.value?.let { loadFollowers(it.pubkey, it.relays) }
            }
        }
    }

    fun confirmFollowersDisclaimer() {
        viewModelScope.launch {
            appSettings.setFollowersDisclaimerShown(true)
            appSettings.setFollowersEnabled(true)
            account.value?.let { loadFollowers(it.pubkey, it.relays) }
        }
    }

    fun dismissFollowersDisclaimer() {
        viewModelScope.launch { appSettings.setFollowersEnabled(false) }
    }

    private fun loadFollowers(pubkey: String, relays: List<String>) {
        viewModelScope.launch {
            _followersBusy.value = true
            try {
                followerRepo.followers(pubkey, relays).collect { state ->
                    _followers.value = state
                }
            } catch (e: Exception) {
                _followers.value = FollowerListRepository.FollowersState(followers = emptyList(), loading = false, error = e.message)
            } finally { _followersBusy.value = false }
        }
    }

    fun refreshFollowers() {
        val acct = account.value ?: return
        followerRepo.invalidate(acct.pubkey)
        loadFollowers(acct.pubkey, acct.relays)
    }

    // ── Add by npub ───────────────────────────────────────────────────────
    fun addByInput(input: String, onDone: (Boolean)->Unit) {
        val acct = account.value ?: return onDone(false)
        viewModelScope.launch {
            // Always add to currently displayed list type: if offline enabled, offline; else online publish
            val isOffline = followsOfflineState.value?.enabled == true
            if (isOffline) {
                val r = offline.addByInput(ListType.FOLLOWS, acct.pubkey, input)
                when (r) {
                    AddResult.ADDED -> say("Added to offline follow list")
                    AddResult.ALREADY_PRESENT -> say("Already in list")
                    AddResult.INVALID -> say("Invalid npub/hex")
                    AddResult.SELF -> say("Can't add yourself")
                    AddResult.LIST_FULL -> say("List full")
                }
                onDone(r == AddResult.ADDED)
            } else {
                val hex = social.tbone.lists.ListFileFormat.normalise(input)
                if (hex == null) { say("Invalid npub/hex"); onDone(false); return@launch }
                if (hex == acct.pubkey) { say("Can't add yourself"); onDone(false); return@launch }
                doFollowOnline(hex)
                onDone(true)
            }
        }
    }

    fun remove(pubkeyHex: String) {
        val acct = account.value ?: return
        viewModelScope.launch {
            val isOffline = followsOfflineState.value?.enabled == true
            if (isOffline) {
                val removed = offline.remove(ListType.FOLLOWS, acct.pubkey, pubkeyHex)
                if (removed != null) say("Removed from offline follow list", undo = removed)
            } else {
                unfollow(pubkeyHex)
            }
        }
    }

    fun undoRemove(entry: ListEntry) {
        val acct = account.value ?: return
        viewModelScope.launch {
            offline.restore(ListType.FOLLOWS, acct.pubkey, entry)
            say("Restored")
        }
    }

    // ── Export / Import ───────────────────────────────────────────────────
    fun exportFileName(): String {
        val pk = account.value?.pubkey ?: "account"
        val date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        return ListFileFormat.suggestedFileName(ListType.FOLLOWS, pk, date)
    }

    fun exportTo(uri: Uri) {
        val acct = account.value ?: return
        viewModelScope.launch {
            try {
                val entries = if (followsOfflineState.value?.enabled == true) {
                    offline.snapshot(ListType.FOLLOWS, acct.pubkey).entries
                } else {
                    _onlineFollowing.value
                }
                val text = ListFileFormat.export(ListType.FOLLOWS, acct.pubkey, entries)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("could not open output stream")
                }
                say("Exported ${entries.size} follows")
            } catch (e: Exception) { say("Export failed: ${e.message}") }
        }
    }

    fun importFrom(uri: Uri, onResult: (ListFileFormat.Parsed)->Unit) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val limit = ListFileFormat.MAX_FILE_CHARS
                        val reader = stream.bufferedReader(Charsets.UTF_8)
                        val sb = StringBuilder()
                        val buf = CharArray(8192)
                        while (true) { val n = reader.read(buf); if (n < 0) break; sb.append(buf,0,n); if (sb.length > limit) break }
                        sb.toString()
                    } ?: error("could not open file")
                }
                val parsed = withContext(Dispatchers.Default) { ListFileFormat.parse(text, ListType.FOLLOWS) }
                if (parsed.error != null) say(parsed.error) else onResult(parsed)
            } catch (e: Exception) { say("Import failed: ${e.message}") }
        }
    }

    fun applyImport(parsed: ListFileFormat.Parsed, replace: Boolean) {
        val acct = account.value ?: return
        viewModelScope.launch {
            val isOffline = followsOfflineState.value?.enabled == true
            if (isOffline) {
                if (replace) {
                    val n = offline.replaceImport(ListType.FOLLOWS, acct.pubkey, parsed.entries)
                    say("Imported — offline list replaced with $n follows")
                } else {
                    val added = offline.mergeImport(ListType.FOLLOWS, acct.pubkey, parsed.entries)
                    say("Imported — $added new follows added")
                }
            } else {
                // Online import: publish merged/replaced to relays
                val current = _onlineFollowing.value
                val incoming = if (replace) parsed.entries else (current + parsed.entries).distinctBy { it.pubkey }
                val newEntries = incoming.map { FollowEntry(it.pubkey, it.relay, it.petname) }
                val signer = signerFactory.forActiveAccount() ?: run { say("No signer"); return@launch }
                val latest = listFetcher.fetchLatest(acct.pubkey, EventKind.FOLLOW_LIST, acct.relays).event
                val keptTags = latest?.tags?.filter { (it.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content != "p" }.orEmpty()
                val pTags = newEntries.map { e ->
                    kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(e.pubkey))
                        if (e.relay.isNotEmpty() || e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.relay))
                        if (e.petname.isNotEmpty()) add(kotlinx.serialization.json.JsonPrimitive(e.petname))
                    }
                }
                val unsigned = social.tbone.nostr.UnsignedEvent(
                    pubkey = signer.pubkey,
                    createdAt = maxOf(System.currentTimeMillis()/1000, (latest?.createdAt ?: 0L) + 1),
                    kind = EventKind.FOLLOW_LIST,
                    content = latest?.content ?: "",
                    tags = pTags + keptTags,
                )
                signer.signEvent(unsigned).onSuccess { ev ->
                    relayPool.publish(ev)
                    accountRepository.updateAccount(acct.pubkey) { it.copy(follows = newEntries.map { it.pubkey }, followEntries = newEntries) }
                    _onlineFollowing.value = newEntries.map { ListEntry(it.pubkey, it.relay, it.petname) }
                    say("Imported & published ${newEntries.size} follows")
                }.onFailure { say("Import publish failed: ${it.message}") }
            }
        }
    }
}
