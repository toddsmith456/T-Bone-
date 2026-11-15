package social.tbone.ui.reactions

import social.tbone.ui.theme.appBorder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import social.tbone.nostr.Event
import social.tbone.ui.theme.BonyColors

private val EmptyReactionContents: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())

/**
 * App-wide multi-emoji reaction state, provided once at the root so every
 * note card's like button can use it without threading parameters through
 * every screen.
 *
 * Option 4: [contentsFlow] is passed as a [StateFlow] rather than collected
 * at the activity root, so incoming reactions only recompose the single
 * [LikeButton] whose `(eventId, activePubkey)` entry actually changed.
 *
 * @param emojis the user's saved reaction emojis — empty when the feature is
 *   off (the like button then behaves as the classic heart).
 * @param contentsFlow live map of reaction content per `eventId|pubkey`.
 * @param reactWith publishes a reaction with the chosen emoji.
 */
@Immutable
data class ReactionConfig(
    val emojis: List<String> = emptyList(),
    val contentsFlow: StateFlow<Map<String, String>> = EmptyReactionContents,
    val reactWith: ((Event, String) -> Unit)? = null,
)

val LocalReactionConfig = compositionLocalOf { ReactionConfig() }

/** True for reaction content that should be drawn as an emoji (not a heart). */
fun isEmojiReaction(content: String?): Boolean {
    if (content.isNullOrBlank()) return false
    if (content == "+" || content == "-") return false
    // NIP-30 custom emoji shortcodes (":name:") have no glyph to draw here.
    if (content.startsWith(":") && content.endsWith(":") && content.length > 2) return false
    return true
}

/**
 * Splits keyboard input into individual emojis.
 *
 * The scanner is grapheme-aware rather than relying on [BreakIterator]: the
 * keyboard panel happily produces multi-code-point emojis and those must stay
 * **one** saved emoji, otherwise reacting with a skin-toned thumb or a family
 * glyph would publish a broken sequence. Handled shapes:
 *
 *  - skin-tone modifiers           `👍🏽`
 *  - variation selectors           `❤️`
 *  - zero-width-joiner sequences   `👨‍👩‍👧`
 *  - keycaps                       `1️⃣`
 *  - regional-indicator flag pairs `🇬🇧`
 *
 * Anything that is not an emoji (letters, digits, whitespace, punctuation) is
 * skipped, so pasted text can never end up in the saved set.
 */
fun extractEmojis(input: String): List<String> {
    if (input.isBlank()) return emptyList()
    val out = mutableListOf<String>()
    var i = 0
    while (i < input.length) {
        val cp = input.codePointAt(i)
        if (!isEmojiCodePoint(cp)) {
            i += Character.charCount(cp)
            continue
        }
        val sb = StringBuilder()
        // Regional indicators pair up into one flag.
        if (cp in REGIONAL_INDICATORS) {
            sb.appendCodePoint(cp)
            i += Character.charCount(cp)
            if (i < input.length) {
                val next = input.codePointAt(i)
                if (next in REGIONAL_INDICATORS) {
                    sb.appendCodePoint(next)
                    i += Character.charCount(next)
                }
            }
            out += sb.toString()
            continue
        }

        sb.appendCodePoint(cp)
        i += Character.charCount(cp)

        // Absorb everything that belongs to this glyph.
        while (i < input.length) {
            val next = input.codePointAt(i)
            when {
                next == VARIATION_SELECTOR_16 || next == VARIATION_SELECTOR_15 -> {
                    sb.appendCodePoint(next); i += Character.charCount(next)
                }
                next in SKIN_TONES -> {
                    sb.appendCodePoint(next); i += Character.charCount(next)
                }
                next == KEYCAP -> {
                    sb.appendCodePoint(next); i += Character.charCount(next)
                }
                next == ZERO_WIDTH_JOINER && i + Character.charCount(next) < input.length -> {
                    val after = input.codePointAt(i + Character.charCount(next))
                    if (!isEmojiCodePoint(after)) break
                    sb.appendCodePoint(next); i += Character.charCount(next)
                    sb.appendCodePoint(after); i += Character.charCount(after)
                }
                else -> break
            }
        }
        out += sb.toString()
    }
    return out
}

/** True for code points that can start an emoji (never letters/digits/space). */
private fun isEmojiCodePoint(cp: Int): Boolean =
    cp in 0x2190..0x2BFF ||              // arrows, dingbats, misc symbols
        cp in 0x1F000..0x1FAFF ||        // emoji, pictographs, symbols
        cp in REGIONAL_INDICATORS ||
        Character.getType(cp) == Character.OTHER_SYMBOL.toInt()

private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF
private val SKIN_TONES = 0x1F3FB..0x1F3FF
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val VARIATION_SELECTOR_15 = 0xFE0E
private const val ZERO_WIDTH_JOINER = 0x200D
private const val KEYCAP = 0x20E3

/**
 * The compact emoji box that pops up over the like button. One row of the
 * user's saved emojis; tapping one reacts and closes the box, tapping
 * outside just closes it.
 */
@Composable
fun EmojiReactionPicker(
    emojis: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.36f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = ((scale.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
                .background(BonyColors.Bg)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clickable { },
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            emojis.forEach { emoji ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onPick(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = emoji, style = TextStyle(fontSize = 22.sp))
                }
            }
        }
        }
    }
}
