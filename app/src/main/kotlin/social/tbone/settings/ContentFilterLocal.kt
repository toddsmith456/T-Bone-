package social.tbone.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import social.tbone.ai.AiFilterConfig
import social.tbone.ai.AiModelManager
import social.tbone.security.PinHasher
import javax.inject.Inject

/** Snapshot of the content filters, read anywhere in the UI. */
data class ContentFilterData(
    val blockedPubkeys: Set<String> = emptySet(),
    val nsfwMode: NsfwMode = NsfwMode.OFF,
    val bleepWords: Set<FilterWord> = emptySet(),
    val hideWords: Set<FilterWord> = emptySet(),
    /**
     * Accounts the user follows.
     *
     * Carried here so that render-time filtering ([NoteCard]) applies the same
     * rule as the view models: a block on someone *else* must never hide a
     * followed author's own note. Without it the note view model classified a
     * note as visible and this second pass then drew nothing, leaving a blank gap
     * in the timeline.
     */
    val followedPubkeys: Set<String> = emptySet(),
    /** Offline on-device AI NSFW scanner preferences. */
    val aiFilterConfig: AiFilterConfig = AiFilterConfig(),
    /** True when at least one verified `.tflite` add-on model is installed on-device. */
    val aiHasInstalledModel: Boolean = false,
) {
    val hasBleep: Boolean get() = bleepWords.isNotEmpty()
    val aiScannerActive: Boolean get() = aiFilterConfig.active && aiHasInstalledModel
}

val LocalContentFilter = staticCompositionLocalOf { ContentFilterData() }

/** Verifies a 4-digit Parental PIN attempt from any UI surface (e.g. revealing AI-veiled media). */
val LocalParentalPinVerifier = staticCompositionLocalOf<suspend (String) -> Boolean> { { false } }

@HiltViewModel
class ContentFilterViewModel @Inject constructor(
    private val appSettings: AppSettings,
    muteRepository: social.tbone.lists.MuteListRepository,
    accountRepository: social.tbone.account.AccountRepository,
    aiModelManager: AiModelManager,
) : ViewModel() {
    private val baseData = combine(
        muteRepository.effectiveMuted,
        appSettings.nsfwMode,
        appSettings.bleepWords,
        appSettings.hideWords,
        accountRepository.activeAccount,
    ) { blocked, nsfwMode, bleep, hide, account ->
        ContentFilterData(
            blockedPubkeys = blocked,
            nsfwMode = nsfwMode,
            bleepWords = bleep,
            hideWords = hide,
            // The account's cached follow set is the same source the feed view
            // model uses, so both passes agree on who is followed.
            followedPubkeys = account?.follows?.toSet().orEmpty(),
        )
    }

    val data: StateFlow<ContentFilterData> = combine(
        baseData,
        appSettings.aiFilterConfig,
        aiModelManager.hasAnyInstalled,
    ) { base, aiConfig, hasModel ->
        base.copy(
            aiFilterConfig = aiConfig,
            aiHasInstalledModel = hasModel,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContentFilterData())

    suspend fun verifyParentalPin(input: String): Boolean {
        val hash = appSettings.getParentalPinHash() ?: return false
        return PinHasher.verify(input, hash)
    }
}

/** Provides [LocalContentFilter] and [LocalParentalPinVerifier] to everything below. */
@Composable
fun ContentFilterProvider(content: @Composable () -> Unit) {
    val viewModel: ContentFilterViewModel = hiltViewModel()
    val data by viewModel.data.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalContentFilter provides data,
        LocalParentalPinVerifier provides viewModel::verifyParentalPin,
    ) {
        content()
    }
}
