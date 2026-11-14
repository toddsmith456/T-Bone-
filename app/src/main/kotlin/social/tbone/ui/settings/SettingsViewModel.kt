package social.tbone.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.logging.LogRepository
import social.tbone.lists.PublishedRelayLists
import social.tbone.lists.RelayListFetcher
import social.tbone.nostr.EventKind
import social.tbone.security.AppLockManager
import social.tbone.settings.AppSettings
import social.tbone.settings.FilterWord
import social.tbone.settings.NsfwMode
import social.tbone.settings.WordMatchMode
import social.tbone.settings.AvatarMode
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality
import social.tbone.settings.OrbotHelper
import social.tbone.settings.ThemeMode
import social.tbone.settings.UiTheme
import social.tbone.stealth.StealthManager
import social.tbone.updates.UpdateInstallResult
import social.tbone.updates.UpdateManager
import social.tbone.updates.UpdateState
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val relayListFetcher: RelayListFetcher,
    val logRepository: LogRepository,
    private val appSettings: AppSettings,
    private val lockManager: AppLockManager,
    private val offlineLists: social.tbone.lists.OfflineListRepository,
    private val updateManager: UpdateManager,
    private val stealthManager: StealthManager,
    private val legacyCleanup: social.tbone.migration.LegacyMiniAppCleanup,
    private val aiModelManager: social.tbone.ai.AiModelManager,
    private val mediaScanCache: social.tbone.ai.MediaScanCache,
    @ApplicationContext context: Context,
) : ViewModel() {

    /** Whether the active account's offline follow list / block list is switched on. */
    val offlineFollowsEnabled: StateFlow<Boolean> = offlineEnabledFlow(social.tbone.lists.ListType.FOLLOWS)
    val offlineMutesEnabled: StateFlow<Boolean> = offlineEnabledFlow(social.tbone.lists.ListType.MUTES)

    val cleanupStatus = legacyCleanup.status
    fun retryLegacyCleanup() = legacyCleanup.retry()

    val updateState: StateFlow<UpdateState> = updateManager.state

    fun checkForUpdates() = updateManager.checkNow()

    fun downloadUpdate() = updateManager.downloadUpdate()

    fun installDownloadedUpdate(context: Context): UpdateInstallResult =
        updateManager.installDownloadedUpdate(context)

    fun saveToDownloads(context: Context) = updateManager.saveToDownloads(context)

    fun installedCertHashes(): List<String> = updateManager.installedCertificateHashes()
    fun downloadedCertHashes(): List<String> = updateManager.downloadedCertificateHashes()
    fun downloadedFileSha256(): String? = updateManager.downloadedFileSha256()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun offlineEnabledFlow(type: social.tbone.lists.ListType): StateFlow<Boolean> =
        accountRepository.activeAccount
            .map { it?.pubkey }
            .flatMapLatest { pk ->
                if (pk == null) kotlinx.coroutines.flow.flowOf(false)
                else offlineLists.state(type, pk).map { it.enabled }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val accounts: StateFlow<List<Account>> = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val confirmExternalLinks: StateFlow<Boolean> = appSettings.confirmExternalLinks

    val torEnabled: StateFlow<Boolean> = appSettings.torEnabled
    val torKillSwitch: StateFlow<Boolean> = appSettings.torKillSwitch

    val pinEnabled: StateFlow<Boolean> = lockManager.isPinEnabled

    val pinAutoLockMinutes: StateFlow<Int> = appSettings.pinAutoLockMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 5)

    val appPinKeypadScrambled: StateFlow<Boolean> = appSettings.appPinKeypadScrambled

    val duressPinEnabled: StateFlow<Boolean> = lockManager.isDuressPinEnabled

    val screenshotBlockEnabled: StateFlow<Boolean> = appSettings.screenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val noteMenuModerationEnabled: StateFlow<Boolean> = appSettings.noteMenuModerationEnabled
    val clientTagEnabled: StateFlow<Boolean> = appSettings.clientTagEnabled

    val imageLoadMode: StateFlow<ImageLoadMode> = appSettings.imageLoadMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageLoadMode.ON)

    val videoQuality: StateFlow<VideoQuality> = appSettings.videoQuality
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoQuality.REGULAR)

    val videoPlaybackMode: StateFlow<VideoPlaybackMode> = appSettings.videoPlaybackMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoPlaybackMode.PLAY_ON_TAP)

    val videoThumbnails: StateFlow<Boolean> = appSettings.videoThumbnails
    val mediaFittedLayout: StateFlow<Boolean> = appSettings.mediaFittedLayout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val imageCacheLightweight: StateFlow<Boolean> = appSettings.imageCacheLightweight
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val avatarMode: StateFlow<AvatarMode> = appSettings.avatarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarMode.REGULAR)

    val avatarAnimated: StateFlow<Boolean> = appSettings.avatarAnimated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themeMode: StateFlow<ThemeMode> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DARK)

    /** Custom accent as "#RRGGBB", or null for the default green. */
    val accentColor: StateFlow<String?> = appSettings.accentColor

    val uiTheme: StateFlow<UiTheme> = appSettings.uiTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiTheme.BONY)

    // ── content filters ─────────────────────────────────────────────────────
    val nsfwMode: StateFlow<NsfwMode> = appSettings.nsfwMode
    val bleepWords: StateFlow<Set<FilterWord>> = appSettings.bleepWords
    val hideWords: StateFlow<Set<FilterWord>> = appSettings.hideWords

    // ── offline ai media scanner (installable add-on models) ────────────────
    val aiFilterConfig: StateFlow<social.tbone.ai.AiFilterConfig> = appSettings.aiFilterConfig
    val aiModelStates: StateFlow<Map<social.tbone.ai.AiModelId, social.tbone.ai.ModelInstallState>> =
        aiModelManager.states
    val aiHasInstalledModel: StateFlow<Boolean> = aiModelManager.hasAnyInstalled

    private val _aiStatusMessage = MutableStateFlow<String?>(null)
    val aiStatusMessage: StateFlow<String?> = _aiStatusMessage.asStateFlow()

    // ── blossom media uploads ────────────────────────────────────────────────
    val blossomServers: StateFlow<Set<String>> = appSettings.blossomServers
    val blossomDefaultServer: StateFlow<String?> = appSettings.blossomDefaultServer
    val blossomBeaverMode: StateFlow<Boolean> = appSettings.blossomBeaverMode
    val blossomCompress: StateFlow<Boolean> = appSettings.blossomCompress
    val blossomAudioCompress: StateFlow<Boolean> = appSettings.blossomAudioCompress

    private val _blossomSyncState = MutableStateFlow(PublishedListSyncState())
    val blossomSyncState: StateFlow<PublishedListSyncState> = _blossomSyncState.asStateFlow()

    /**
     * Fetches kind 10063 and applies it to the local server list.
     *
     * [replace] = false → MERGE: published servers are added, local ones kept.
     * [replace] = true  → REPLACE: the local list becomes exactly the published
     *   list, so servers you only had locally are removed (including the
     *   defaults). The published list is the account's own statement of which
     *   servers it uses, so replacing is a legitimate choice — it is just
     *   destructive, hence the explicit option instead of a silent behaviour.
     */
    fun syncBlossomServers(replace: Boolean = false) {
        if (_blossomSyncState.value.syncing) return
        viewModelScope.launch {
            _blossomSyncState.value = PublishedListSyncState(syncing = true, message = "syncing published list…")
            try {
                val account = accountRepository.activeAccount.first()
                    ?: error("Add an account before syncing a published Blossom list.")
                val result = relayListFetcher.fetchLatest(
                    pubkey = account.pubkey,
                    kind = EventKind.BLOSSOM_SERVER_LIST,
                    seedRelays = account.relays,
                )
                val event = result.event ?: run {
                    _blossomSyncState.value = PublishedListSyncState(
                        message = if (result.reachedRelays) "No published Blossom server list was found."
                        else "Could not reach your relays. Try again.",
                        isError = true,
                    )
                    return@launch
                }
                val imported = PublishedRelayLists.blossomServers(event)
                if (imported.isEmpty()) {
                    _blossomSyncState.value = PublishedListSyncState(
                        message = "The published list contains no valid Blossom servers.",
                        isError = true,
                    )
                    return@launch
                }
                val before = appSettings.blossomServers.value
                if (replace) {
                    val next = imported.toSet()
                    appSettings.setBlossomServers(next)
                    // The old default may no longer exist; a dangling default would
                    // never match the pool and would silently stop being applied.
                    val currentDefault = appSettings.blossomDefaultServer.value
                    if (currentDefault != null && currentDefault !in next) {
                        appSettings.setBlossomDefaultServer(null)
                    }
                    val removed = (before - next).size
                    _blossomSyncState.value = PublishedListSyncState(
                        message = "Replaced your list with ${next.size} published Blossom server${if (next.size == 1) "" else "s"}" +
                            if (removed > 0) " — $removed local server${if (removed == 1) "" else "s"} removed." else ".",
                    )
                } else {
                    val merged = before + imported
                    appSettings.setBlossomServers(merged)
                    val added = (merged - before).size
                    _blossomSyncState.value = PublishedListSyncState(
                        message = "Merged ${imported.size} published Blossom server${if (imported.size == 1) "" else "s"}" +
                            "${if (added > 0) " ($added new)" else ""}; your local servers were kept.",
                    )
                }
            } catch (error: Exception) {
                _blossomSyncState.value = PublishedListSyncState(
                    message = error.message ?: "Could not sync the published Blossom list.",
                    isError = true,
                )
            }
        }
    }

    fun setNoteMenuModerationEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setNoteMenuModerationEnabled(enabled) }
    }

    fun setClientTagEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setClientTagEnabled(enabled) }
    }

    fun setBlossomBeaverMode(enabled: Boolean) {
        viewModelScope.launch { appSettings.setBlossomBeaverMode(enabled) }
    }

    /**
     * Enables or disables one server in the pool. Disabling keeps the entry in
     * DataStore so it can be turned back on; it only leaves the *enabled* set that
     * uploads draw from.
     */
    fun setBlossomServerEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = appSettings.blossomServers.value
            appSettings.setBlossomServers(if (enabled) current + url else current - url)
            if (!enabled && appSettings.blossomDefaultServer.value == url) {
                appSettings.setBlossomDefaultServer(null)
            }
        }
    }

    fun addBlossomServer(url: String) {
        viewModelScope.launch { appSettings.addBlossomServer(url) }
    }

    fun removeBlossomServer(url: String) {
        viewModelScope.launch { appSettings.removeBlossomServer(url) }
    }

    fun setBlossomDefaultServer(url: String?) {
        viewModelScope.launch { appSettings.setBlossomDefaultServer(url) }
    }

    fun setBlossomCompress(enabled: Boolean) {
        viewModelScope.launch { appSettings.setBlossomCompress(enabled) }
    }

    fun setBlossomAudioCompress(enabled: Boolean) {
        viewModelScope.launch { appSettings.setBlossomAudioCompress(enabled) }
    }

    // ── screen time (parental) ───────────────────────────────────────────────
    val screenTimeEnabled: StateFlow<Boolean> = appSettings.screenTimeEnabled
    val screenTimeMinutes: StateFlow<Int> = appSettings.screenTimeMinutes

    fun setScreenTimeEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setScreenTimeEnabled(enabled) }
    }

    fun setScreenTimeMinutes(minutes: Int) {
        viewModelScope.launch { appSettings.setScreenTimeMinutes(minutes.coerceIn(5, 24 * 60)) }
    }

    // ── multi emoji reactions ───────────────────────────────────────────────
    val multiReactionsEnabled: StateFlow<Boolean> = appSettings.multiReactionsEnabled
    val reactionEmojis: StateFlow<List<String>> = appSettings.reactionEmojis

    fun setMultiReactionsEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setMultiReactionsEnabled(enabled) }
    }

    fun addReactionEmojis(emojis: List<String>) {
        if (emojis.isEmpty()) return
        viewModelScope.launch {
            appSettings.setReactionEmojis(appSettings.reactionEmojis.value + emojis)
        }
    }

    fun removeReactionEmoji(emoji: String) {
        viewModelScope.launch {
            appSettings.setReactionEmojis(appSettings.reactionEmojis.value - emoji)
        }
    }

    // ── parental pin (locks the filter settings) ────────────────────────────
    val parentalPinEnabled: StateFlow<Boolean> = appSettings.parentalPinEnabled

    /**
     * Whether the Content Filters folder (filters + screen time) is unlocked.
     * Shared across the folder's screens via [AppSettings]; session-only.
     */
    val filtersUnlocked: StateFlow<Boolean> = appSettings.parentalUnlocked

    fun unlockFilters() {
        appSettings.setParentalUnlocked(true)
    }

    fun relockFilters() {
        appSettings.setParentalUnlocked(false)
    }

    /** Set by the Content Filters folder screen: relock when it is popped. */
    private var relockOnClear = false
    fun markAsParentalGate() { relockOnClear = true }

    override fun onCleared() {
        if (relockOnClear) appSettings.setParentalUnlocked(false)
        super.onCleared()
    }

    suspend fun verifyParentalPin(input: String): Boolean {
        val stored = appSettings.getParentalPinHash() ?: return false
        val ok = social.tbone.security.PinHasher.verify(input, stored)
        if (ok && social.tbone.security.PinHasher.isLegacy(stored)) {
            appSettings.setParentalPinHash(social.tbone.security.PinHasher.hash(input))
        }
        return ok
    }

    fun setParentalPin(pin: String) {
        viewModelScope.launch {
            appSettings.setParentalPinHash(social.tbone.security.PinHasher.hash(pin))
            appSettings.setParentalPinEnabled(true)
            appSettings.setParentalUnlocked(true)
        }
    }

    fun disableParentalPin() {
        viewModelScope.launch {
            appSettings.setParentalPinEnabled(false)
            appSettings.setParentalPinHash("")
            appSettings.setParentalUnlocked(false)
        }
    }



    fun setNsfwMode(mode: NsfwMode) {
        viewModelScope.launch { appSettings.setNsfwMode(mode) }
    }

    fun setAiNsfwMode(mode: NsfwMode) {
        viewModelScope.launch { appSettings.setAiNsfwMode(mode) }
    }

    fun setAiSensitivity(sensitivity: social.tbone.ai.AiSensitivity) {
        viewModelScope.launch { appSettings.setAiSensitivity(sensitivity) }
    }

    fun setAiScanMode(mode: social.tbone.ai.AiScanMode) {
        viewModelScope.launch { appSettings.setAiScanMode(mode) }
    }

    fun setAiScanAvatars(enabled: Boolean) {
        viewModelScope.launch { appSettings.setAiScanAvatars(enabled) }
    }

    fun setAiScanBanners(enabled: Boolean) {
        viewModelScope.launch { appSettings.setAiScanBanners(enabled) }
    }

    fun setAiExemptFollows(enabled: Boolean) {
        viewModelScope.launch { appSettings.setAiExemptFollows(enabled) }
    }

    fun setAiRequirePinToReveal(enabled: Boolean) {
        viewModelScope.launch { appSettings.setAiRequirePinToReveal(enabled) }
    }

    fun downloadAiModel(id: social.tbone.ai.AiModelId) {
        _aiStatusMessage.value = null
        aiModelManager.downloadModel(id)
    }

    fun cancelAiModelDownload(id: social.tbone.ai.AiModelId) {
        aiModelManager.cancelDownload(id)
    }

    fun removeAiModel(id: social.tbone.ai.AiModelId) {
        _aiStatusMessage.value = null
        aiModelManager.removeModel(id)
    }

    fun importAiModel(uri: Uri) {
        viewModelScope.launch {
            _aiStatusMessage.value = "verifying local model file…"
            val result = aiModelManager.importLocalModel(uri)
            _aiStatusMessage.value = result.fold(
                onSuccess = { spec -> "installed ${spec.displayName}" },
                onFailure = { err -> err.message ?: "model import failed" },
            )
        }
    }

    fun clearAiScanCache() {
        viewModelScope.launch {
            mediaScanCache.clearAll()
            _aiStatusMessage.value = "local scan cache cleared"
        }
    }
    fun addBleepWord(word: String, mode: WordMatchMode = WordMatchMode.WHOLE_WORD) {
        viewModelScope.launch { appSettings.addBleepWord(word, mode) }
    }
    fun removeBleepWord(word: FilterWord) {
        viewModelScope.launch { appSettings.removeBleepWord(word) }
    }
    fun toggleBleepWordMode(word: FilterWord) {
        val next = if (word.mode == WordMatchMode.WHOLE_WORD) WordMatchMode.ANYWHERE else WordMatchMode.WHOLE_WORD
        viewModelScope.launch { appSettings.setBleepWordMode(word, next) }
    }
    fun addHideWord(word: String, mode: WordMatchMode = WordMatchMode.WHOLE_WORD) {
        viewModelScope.launch { appSettings.addHideWord(word, mode) }
    }
    fun removeHideWord(word: FilterWord) {
        viewModelScope.launch { appSettings.removeHideWord(word) }
    }
    fun toggleHideWordMode(word: FilterWord) {
        val next = if (word.mode == WordMatchMode.WHOLE_WORD) WordMatchMode.ANYWHERE else WordMatchMode.WHOLE_WORD
        viewModelScope.launch { appSettings.setHideWordMode(word, next) }
    }

    /** True if Orbot is installed — checked once at construction, no async probe needed. */
    val orbotInstalled: Boolean = OrbotHelper.isInstalled(context)

    fun setTorKillSwitch(enabled: Boolean) {
        viewModelScope.launch { appSettings.setTorKillSwitch(enabled) }
    }

    fun setTorEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setTorEnabled(enabled) }
    }

    fun setImageLoadMode(mode: ImageLoadMode) {
        viewModelScope.launch { appSettings.setImageLoadMode(mode) }
    }

    fun setVideoQuality(quality: VideoQuality) {
        viewModelScope.launch { appSettings.setVideoQuality(quality) }
    }

    fun setVideoPlaybackMode(mode: VideoPlaybackMode) {
        viewModelScope.launch { appSettings.setVideoPlaybackMode(mode) }
    }

    fun setVideoThumbnails(enabled: Boolean) {
        viewModelScope.launch { appSettings.setVideoThumbnails(enabled) }
    }

    fun setMediaFittedLayout(enabled: Boolean) {
        viewModelScope.launch { appSettings.setMediaFittedLayout(enabled) }
    }

    fun setImageCacheLightweight(enabled: Boolean) {
        viewModelScope.launch { appSettings.setImageCacheLightweight(enabled) }
    }

    fun setAvatarMode(mode: AvatarMode) {
        viewModelScope.launch { appSettings.setAvatarMode(mode) }
    }

    fun setAvatarAnimated(animated: Boolean) {
        viewModelScope.launch { appSettings.setAvatarAnimated(animated) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }

    fun setAccentColor(hex: String?) {
        viewModelScope.launch { appSettings.setAccentColor(hex) }
    }

    fun setUiTheme(theme: UiTheme) {
        viewModelScope.launch { appSettings.setUiTheme(theme) }
    }

    fun setConfirmExternalLinks(enabled: Boolean) {
        viewModelScope.launch { appSettings.setConfirmExternalLinks(enabled) }
    }

    fun setScreenshotBlockEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setScreenshotBlockEnabled(enabled) }
    }

    fun setAppPinKeypadScrambled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setAppPinKeypadScrambled(enabled) }
    }

    fun setPinAutoLockMinutes(minutes: Int) {
        viewModelScope.launch { appSettings.setPinAutoLockMinutes(minutes) }
    }

    fun disablePin() {
        viewModelScope.launch { lockManager.disablePin() }
    }

    fun disableDuressPin() {
        viewModelScope.launch { lockManager.disableDuressPin() }
    }

    fun switchAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.setActiveAccount(pubkey) }
    }

    fun removeAccount(pubkey: String) {
        viewModelScope.launch { accountRepository.removeAccount(pubkey) }
    }

    // ── Stealth: notepad front ───────────────────────────────────────────────
    val notepadFrontEnabled: StateFlow<Boolean> = appSettings.notepadFrontEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setNotepadFrontEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                stealthManager.setNotepadFront(enabled)
                appSettings.setNotepadFrontEnabled(enabled)
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Could not switch launcher disguise")
            }
        }
    }

    suspend fun markNotepadDisclaimerShown() {
        appSettings.setNotepadFrontDisclaimerShown(true)
    }

    // ── Stealth: hidden accounts ─────────────────────────────────────────────
    private val _hiddenUnlockMessage = MutableStateFlow<String?>(null)
    private val hiddenUnlockRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    val hiddenUnlockMessage: StateFlow<String?> = _hiddenUnlockMessage.asStateFlow()

    fun hideAccount(pubkey: String, pin: String) {
        viewModelScope.launch {
            val ok = try { accountRepository.setHidden(pubkey, true, pin) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            _hiddenUnlockMessage.value = if (ok) "Account hidden — your PIN will bring it back" else "Could not hide account"
            kotlinx.coroutines.delay(2500)
            _hiddenUnlockMessage.value = null
        }
    }

    fun unhideAccount(pubkey: String) {
        viewModelScope.launch {
            val ok = try { accountRepository.setHidden(pubkey, false) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            if (!ok) _hiddenUnlockMessage.value = "Could not unhide account"
        }
    }

    fun unlockHiddenAccount(pin: String) {
        if (!hiddenUnlockRunning.compareAndSet(false, true)) return
        viewModelScope.launch {
          try {
            val message = try {
                if (accountRepository.unlockHiddenByPin(pin)) "Matching hidden accounts unlocked" else "Wrong PIN"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { "Could not unlock account" }
            _hiddenUnlockMessage.value = message
            kotlinx.coroutines.delay(2000)
            _hiddenUnlockMessage.value = null
          } finally {
            hiddenUnlockRunning.set(false)
          }
        }
    }

    fun clearHiddenMessage() { _hiddenUnlockMessage.value = null }
}
