package social.tbone.toolbox.feeds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import social.tbone.home.FeedSource
import social.tbone.home.HomeLayoutRepository
import social.tbone.nostr.Nip19
import javax.inject.Inject

/**
 * Backs the viewer's launcher with the one thing it needs from storage: the
 * user's saved feeds, so a feed already on the home screen can be opened in the
 * viewer with one tap instead of being retyped.
 */
@HiltViewModel
class FeedViewerLauncherViewModel @Inject constructor(
    homeLayouts: HomeLayoutRepository,
) : ViewModel() {

    val savedFeeds: StateFlow<List<FeedSource>> = homeLayouts.layout
        .map { layout -> layout.slots.map { it.source } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Turns pasted text into public keys.
     *
     * Accepts a newline-, comma- or space-separated blob of npubs or 64-character
     * hex keys, because that is what people actually paste — from a NIP-51 set, a
     * DM, or a text file. Anything unparseable is dropped rather than failing the
     * whole list: one typo in forty keys should not stop the other thirty-nine.
     */
    fun parseKeys(raw: String): List<String> =
        raw.split('\n', ',', ' ', '\t', ';', '"', '\'')
            .mapNotNull { chunk ->
                val trimmed = chunk.trim().removePrefix("nostr:")
                when {
                    trimmed.length == 64 && trimmed.all { it in HEX } -> trimmed.lowercase()
                    trimmed.startsWith("npub1") -> Nip19.npubToHex(trimmed)?.lowercase()
                    else -> null
                }
            }
            .distinct()

    private companion object {
        const val HEX = "0123456789abcdefABCDEF"
    }
}
