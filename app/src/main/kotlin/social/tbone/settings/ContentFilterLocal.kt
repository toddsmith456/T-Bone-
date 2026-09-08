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
    val hideNsfw: Boolean = false,
    val bleepWords: Set<String> = emptySet(),
    val hideWords: Set<String> = emptySet(),
) {
    val hasBleep: Boolean get() = bleepWords.isNotEmpty()
}

val LocalContentFilter = staticCompositionLocalOf { ContentFilterData() }

@HiltViewModel
class ContentFilterViewModel @Inject constructor(
    appSettings: AppSettings,
) : ViewModel() {
    val data: StateFlow<ContentFilterData> = combine(
        appSettings.blockedPubkeys,
        appSettings.hideNsfw,
        appSettings.bleepWords,
        appSettings.hideWords,
    ) { blocked, nsfw, bleep, hide ->
        ContentFilterData(blocked, nsfw, bleep, hide)
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
