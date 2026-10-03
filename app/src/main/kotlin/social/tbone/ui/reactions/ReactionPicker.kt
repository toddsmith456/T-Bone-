package social.tbone.ui.reactions

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import social.tbone.nostr.Event
import social.tbone.ui.theme.BonyColors
import java.text.BreakIterator

/**
 * App-wide multi-emoji reaction state, provided once at the root so every
 * note card's like button can use it without threading parameters through
 * every screen.
 *
 * @param emojis the user's saved reaction emojis — empty when the feature is
 *   off (the like button then behaves as the classic heart).
 * @param contents reaction content per `eventId|pubkey` (see reactionKey).
 * @param reactWith publishes a reaction with the chosen emoji.
 */
data class ReactionConfig(
    val emojis: List<String> = emptyList(),
    val contents: Map<String, String> = emptyMap(),
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
 * Splits keyboard input into individual emojis (grapheme clusters), keeping
 * only clusters that contain no letters, digits or whitespace — so ZWJ
 * sequences, skin tones and flags stay intact while stray text is ignored.
 */
fun extractEmojis(input: String): List<String> {
    if (input.isBlank()) return emptyList()
    val out = mutableListOf<String>()
    val it = BreakIterator.getCharacterInstance()
    it.setText(input)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        val cluster = input.substring(start, end)
        val looksLikeEmoji = cluster.isNotBlank() &&
            cluster.none { c -> c.isLetterOrDigit() || c.isWhitespace() } &&
            cluster.codePoints().anyMatch { cp ->
                val type = Character.getType(cp)
                cp > 0x2000 && (type == Character.OTHER_SYMBOL.toInt() ||
                    type == Character.SURROGATE.toInt() ||
                    Character.isSupplementaryCodePoint(cp) ||
                    cp in 0x2190..0x2BFF)
            }
        if (looksLikeEmoji) out += cluster
        start = end
        end = it.next()
    }
    return out
}

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
    val density = LocalDensity.current
    // Sit just above the button, right-aligned with it.
    val yOffset = with(density) { (-52).dp.roundToPx() }
    val scale = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            1f,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        )
    }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, yOffset),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Row(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    alpha = ((scale.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 1f)
                }
                .background(BonyColors.Bg)
                .border(1.dp, BonyColors.RuleStrong)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
