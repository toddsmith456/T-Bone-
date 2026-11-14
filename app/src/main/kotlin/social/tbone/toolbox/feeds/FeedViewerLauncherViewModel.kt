package social.tbone.toolbox.feeds

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import social.tbone.nostr.Nip19
import javax.inject.Inject

/**
 * Backs the viewer's launcher. All it does is turn pasted text into public keys;
 * the launcher deliberately does not read the home layout any more — a "saved
 * feeds" section duplicated rows already reachable from the home screen and cost
 * vertical space on every visit to the tool.
 */
@HiltViewModel
class FeedViewerLauncherViewModel @Inject constructor() : ViewModel() {

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
