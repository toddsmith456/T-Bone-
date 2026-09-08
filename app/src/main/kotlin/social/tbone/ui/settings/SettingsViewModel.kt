package social.tbone.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.logging.LogRepository
import social.tbone.security.AppLockManager
import social.tbone.settings.AppSettings
import social.tbone.settings.AvatarMode
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.OrbotHelper
import social.tbone.settings.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    val logRepository: LogRepository,
    private val appSettings: AppSettings,
    private val lockManager: AppLockManager,
    @ApplicationContext context: Context,
) : ViewModel() {

    val accounts: StateFlow<List<Account>> = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeAccount: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val torEnabled: StateFlow<Boolean> = appSettings.torEnabled

    val pinEnabled: StateFlow<Boolean> = lockManager.isPinEnabled

    val duressPinEnabled: StateFlow<Boolean> = lockManager.isDuressPinEnabled

    val screenshotBlockEnabled: StateFlow<Boolean> = appSettings.screenshotBlockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val imageLoadMode: StateFlow<ImageLoadMode> = appSettings.imageLoadMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ImageLoadMode.ON)

    val avatarMode: StateFlow<AvatarMode> = appSettings.avatarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AvatarMode.REGULAR)

    val avatarAnimated: StateFlow<Boolean> = appSettings.avatarAnimated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val themeMode: StateFlow<ThemeMode> = appSettings.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.DARK)

    /** Custom accent as "#RRGGBB", or null for the default green. */
    val accentColor: StateFlow<String?> = appSettings.accentColor

    // ── content filters ─────────────────────────────────────────────────────
    val hideNsfw: StateFlow<Boolean> = appSettings.hideNsfw
    val bleepWords: StateFlow<Set<String>> = appSettings.bleepWords
    val hideWords: StateFlow<Set<String>> = appSettings.hideWords

    // ── blossom media uploads ────────────────────────────────────────────────
    val blossomServers: StateFlow<Set<String>> = appSettings.blossomServers
    val blossomDefaultServer: StateFlow<String?> = appSettings.blossomDefaultServer
    val blossomCompress: StateFlow<Boolean> = appSettings.blossomCompress

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

    // ── screen time (parental) ───────────────────────────────────────────────
    val screenTimeEnabled: StateFlow<Boolean> = appSettings.screenTimeEnabled
    val screenTimeMinutes: StateFlow<Int> = appSettings.screenTimeMinutes

    fun setScreenTimeEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setScreenTimeEnabled(enabled) }
    }

    fun setScreenTimeMinutes(minutes: Int) {
        viewModelScope.launch { appSettings.setScreenTimeMinutes(minutes.coerceIn(5, 24 * 60)) }
    }

    // ── parental pin (locks the filter settings) ────────────────────────────
    val parentalPinEnabled: StateFlow<Boolean> = appSettings.parentalPinEnabled

    /** Whether the filters screen was unlocked this visit (session-only). */
    private val _filtersUnlocked = MutableStateFlow(false)
    val filtersUnlocked: StateFlow<Boolean> = _filtersUnlocked.asStateFlow()

    fun unlockFilters() {
        _filtersUnlocked.value = true
    }

    fun relockFilters() {
        _filtersUnlocked.value = false
    }

    suspend fun verifyParentalPin(input: String): Boolean {
        val stored = appSettings.getParentalPinHash() ?: return false
        return sha256(input) == stored
    }

    fun setParentalPin(pin: String) {
        viewModelScope.launch {
            appSettings.setParentalPinHash(sha256(pin))
            appSettings.setParentalPinEnabled(true)
            _filtersUnlocked.value = true
        }
    }

    fun disableParentalPin() {
        viewModelScope.launch {
            appSettings.setParentalPinEnabled(false)
            appSettings.setParentalPinHash("")
            _filtersUnlocked.value = false
        }
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun toggleHideNsfw() {
        viewModelScope.launch { appSettings.setHideNsfw(!appSettings.hideNsfw.value) }
    }
    fun addBleepWord(word: String) {
        viewModelScope.launch { appSettings.addBleepWord(word) }
    }
    fun removeBleepWord(word: String) {
        viewModelScope.launch { appSettings.removeBleepWord(word) }
    }
    fun addHideWord(word: String) {
        viewModelScope.launch { appSettings.addHideWord(word) }
    }
    fun removeHideWord(word: String) {
        viewModelScope.launch { appSettings.removeHideWord(word) }
    }

    /** True if Orbot is installed — checked once at construction, no async probe needed. */
    val orbotInstalled: Boolean = OrbotHelper.isInstalled(context)

    fun setTorEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setTorEnabled(enabled) }
    }

    fun setImageLoadMode(mode: ImageLoadMode) {
        viewModelScope.launch { appSettings.setImageLoadMode(mode) }
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

    fun setScreenshotBlockEnabled(enabled: Boolean) {
        viewModelScope.launch { appSettings.setScreenshotBlockEnabled(enabled) }
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
}
