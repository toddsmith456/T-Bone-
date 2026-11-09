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
import javax.inject.Inject

/** Snapshot of the content filters, read anywhere in the UI. */
data class ContentFilterData(
    val blockedPubkeys: Set<String> = emptySet(),
    val nsfwMode: NsfwMode = NsfwMode.OFF,
    val bleepWords: Set<FilterWord> = emptySet(),
    val hideWords: Set<FilterWord> = emptySet(),
) {
    val hasBleep: Boolean get() = bleepWords.isNotEmpty()
}

val LocalContentFilter = staticCompositionLocalOf { ContentFilterData() }

@HiltViewModel
class ContentFilterViewModel @Inject constructor(
    appSettings: AppSettings,
    muteRepository: social.tbone.lists.MuteListRepository,
) : ViewModel() {
    val data: StateFlow<ContentFilterData> = combine(
        muteRepository.effectiveMuted,
        appSettings.nsfwMode,
        appSettings.bleepWords,
        appSettings.hideWords,
    ) { blocked, nsfwMode, bleep, hide ->
        ContentFilterData(blocked, nsfwMode, bleep, hide)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContentFilterData())
}

/** Provides [LocalContentFilter] to everything below. */
@Composable
fun ContentFilterProvider(content: @Composable () -> Unit) {
    val viewModel: ContentFilterViewModel = hiltViewModel()
    val data by viewModel.data.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalContentFilter provides data) {
        content()
    }
}
