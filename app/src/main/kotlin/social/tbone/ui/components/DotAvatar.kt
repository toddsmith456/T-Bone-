package social.tbone.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.JetBrainsMono

/**
 * 4×4 deterministic dot-grid avatar.
 *
 * Grid derived from bits 66–81 of SHA-256(pubkey_bytes).
 * First character of [displayName] (or first hex char of [pubkeyHex]) overlaid centered.
 * Square, hairline [BonyColors.Rule] border, never circular.
 */
@Composable
fun DotAvatar(
    pubkeyHex: String,
    displayName: String? = null,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    val grid = remember(pubkeyHex) { Phrase.avatarGrid(pubkeyHex) }
    val label = remember(displayName, pubkeyHex) {
        (displayName?.firstOrNull() ?: pubkeyHex.firstOrNull())?.uppercaseChar()?.toString() ?: "?"
    }

    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, BonyColors.Rule),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cellW = this.size.width / 4f
            val cellH = this.size.height / 4f
            val dotColor = BonyColors.Accent.copy(alpha = 0.5f)

            grid.forEachIndexed { i, on ->
                if (!on) return@forEachIndexed
                val col = i % 4
                val row = i / 4
                drawRect(
                    color = dotColor,
                    topLeft = Offset(col * cellW + 1f, row * cellH + 1f),
                    size = Size(cellW - 2f, cellH - 2f),
                )
            }
        }

        Text(
            text = label,
            color = BonyColors.Text,
            fontFamily = JetBrainsMono,
            fontSize = (size.value * 0.38f).sp,
            lineHeight = (size.value * 0.38f).sp,
        )
    }
}
