package social.tbone.ui.toolbox.webxdc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.tbone.account.Account
import social.tbone.account.AccountRepository
import social.tbone.nostr.ProfileContent
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.webxdc.XdcCustomTool
import social.tbone.webxdc.SavedXdcApp
import social.tbone.webxdc.XdcRepository
import social.tbone.webxdc.XdcUpdateStore
import java.io.File
import javax.inject.Inject

/**
 * State for the single-app player. The app itself is served to a sandboxed
 * WebView; this view model only resolves the saved package and the identity
 * handed to `window.webxdc.selfAddr` / `selfName`.
 */
@HiltViewModel
class XdcPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: XdcRepository,
    accountRepository: AccountRepository,
    profileRepository: ProfileRepository,
    private val appSettings: AppSettings,
) : ViewModel() {

    val appId: String = checkNotNull(savedStateHandle["appId"]) { "appId is required" }

    val app: StateFlow<SavedXdcApp?> = repository.savedApps
        .map { apps -> apps.firstOrNull { it.id == appId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val libraryLoaded: StateFlow<Boolean> = repository.loaded

    val account: StateFlow<Account?> = accountRepository.activeAccount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<Map<String, ProfileContent>> = profileRepository.profiles

    fun store(): XdcUpdateStore = repository.updateStore(appId)

    fun filesDir(): File = repository.filesDir(appId)

    fun iconFile(app: SavedXdcApp): File? = repository.iconFile(app)

    val customTools: StateFlow<List<XdcCustomTool>> = appSettings.xdcCustomTools

    fun toolFor(appId: String): XdcCustomTool? =
        appSettings.xdcCustomTools.value.firstOrNull { it.appId == appId }

    /** Promotes this app to a toolbox tool, replacing any previous icon/name. */
    fun addAsTool(app: SavedXdcApp, name: String, iconId: String) {
        viewModelScope.launch {
            val tools = appSettings.xdcCustomTools.value.filterNot { it.appId == app.id }
            appSettings.setXdcCustomTools(
                tools + XdcCustomTool(
                    appId = app.id,
                    name = name.trim().ifEmpty { app.name }.take(40),
                    iconId = iconId,
                    addedAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
