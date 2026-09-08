package social.tbone.ui.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import social.tbone.nostr.Event
import social.tbone.nostr.Nip88
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/** Fixed bar height for poll results — stable, no measurement needed. */
private val RESULT_BAR_HEIGHT = 8.dp

/**
 * NIP-88 poll card, mirroring Wisp's PollSection but compact and stable:
 *  - Before voting: option rows (radio for single, checkbox + VOTE for multi),
 *    with a hairline divider between options.
 *  - After voting or when ended: animated percentage bars with a check on the
 *    user's choices and a total-votes line.
 *
 * All parsing is defensive — malformed poll tags can never crash the card.
 */
@Composable
fun PollCard(
    poll: Event,
    voteCounts: Map<String, Int>,
    totalVotes: Int,
    userVotes: List<String>,
    onVote: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(poll.id) { Nip88.parsePollOptions(poll) }
    if (options.isEmpty()) return // nothing to render — never crash

    val pollType = remember(poll.id) { Nip88.parsePollType(poll) }
    val isEnded = remember(poll.id) { Nip88.isPollEnded(poll) }
    val hasVoted = userVotes.isNotEmpty()
    val showResults = hasVoted || isEnded
    val total = totalVotes.coerceAtLeast(0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt),
    ) {
        if (showResults) {
            options.forEachIndexed { index, option ->
                if (index > 0) PollDivider()
                val count = (voteCounts[option.id] ?: 0).coerceAtLeast(0)
                val percentage = if (total > 0) count.toFloat() / total else 0f
                PollResultRow(
                    label = option.label,
                    percentage = percentage,
                    count = count,
                    isUserChoice = option.id in userVotes,
                )
            }
            Text(
                text = "$total vote${if (total != 1) "s" else ""}" +
                    (if (isEnded) " · poll ended" else ""),
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
            )
        } else {
            if (pollType == Nip88.PollType.SINGLECHOICE) {
                options.forEachIndexed { index, option ->
                    if (index > 0) PollDivider()
                    PollOptionRow(
                        label = option.label,
                        selected = false,
                        isRadio = true,
                        onClick = { onVote(listOf(option.id)) },
                    )
                }
            } else {
                // Multiple choice — local selection, submit via VOTE button.
                var selected by remember(poll.id) { mutableStateOf<Set<String>>(emptySet()) }
                options.forEachIndexed { index, option ->
                    if (index > 0) PollDivider()
                    PollOptionRow(
                        label = option.label,
                        selected = option.id in selected,
                        isRadio = false,
                        onClick = {
                            selected = if (option.id in selected) selected - option.id
                            else selected + option.id
                        },
                    )
                }
                if (selected.isNotEmpty()) {
                    PollDivider()
                    Box(
                        modifier = Modifier
                            .padding(12.dp)
                            .border(1.dp, BonyColors.Accent, RectangleShape)
                            .clickable { onVote(selected.toList()) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "VOTE",
                            style = BonyType.tag.copy(color = BonyColors.Accent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PollDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BonyColors.Rule),
    )
}

@Composable
private fun PollOptionRow(
    label: String,
    selected: Boolean,
    isRadio: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) BonyColors.AccentBg else BonyColors.SurfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                isRadio -> if (selected) "◉" else "○"
                selected -> "☑"
                else -> "☐"
            },
            style = BonyType.body.copy(
                color = if (selected) BonyColors.Accent else BonyColors.TextMute,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label.ifBlank { "(untitled)" },
            style = BonyType.body.copy(color = BonyColors.Text),
        )
    }
}

@Composable
private fun PollResultRow(
    label: String,
    percentage: Float,
    count: Int,
    isUserChoice: Boolean,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = percentage.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600),
        label = "pollBar",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isUserChoice) {
                    Text(
                        text = "✓ ",
                        style = BonyType.body.copy(color = BonyColors.Accent),
                    )
                }
                Text(
                    text = label.ifBlank { "(untitled)" },
                    style = BonyType.body.copy(color = BonyColors.Text),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(RESULT_BAR_HEIGHT)
                    .background(BonyColors.Rule),
            ) {
                // Fill — fixed-height, no runtime measurement.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            fraction = if (animatedFraction > 0f) animatedFraction.coerceIn(0f, 1f) else 0.001f,
                        )
                        .height(RESULT_BAR_HEIGHT)
                        .background(BonyColors.Accent.copy(alpha = 0.55f)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "${(percentage * 100).toInt()}% ($count)",
            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
        )
    }
}
