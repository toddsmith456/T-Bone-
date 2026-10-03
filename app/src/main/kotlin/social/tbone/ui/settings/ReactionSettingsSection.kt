package social.tbone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.settings.AppSettings
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.reactions.extractEmojis
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Settings → REACTIONS. Turns on multi emoji reactions and manages up to
 * [AppSettings.MAX_REACTION_EMOJIS] saved emojis, typed from the keyboard's
 * own emoji panel. With 2+ saved, tapping like opens a compact picker.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionSettingsSection(viewModel: SettingsViewModel) {
    val enabled by viewModel.multiReactionsEnabled.collectAsStateWithLifecycle()
    val emojis by viewModel.reactionEmojis.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val max = AppSettings.MAX_REACTION_EMOJIS
    val full = emojis.size >= max

    SectionHeader("REACTIONS")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("multi emoji reactions", style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                text = if (enabled) {
                    "choose from your saved emojis when you tap like"
                } else {
                    "off · like is a heart"
                },
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        BonyToggle(
            checked = enabled,
            enabled = true,
            onCheckedChange = { viewModel.setMultiReactionsEnabled(it) },
        )
    }

    if (enabled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            Text(
                text = "saved emojis · ${emojis.size}/$max",
                style = BonyType.meta.copy(color = BonyColors.TextDim),
            )
            Spacer(Modifier.height(8.dp))

            if (emojis.isEmpty()) {
                Text(
                    text = "none yet — add some from your keyboard below",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    emojis.forEach { emoji ->
                        Row(
                            modifier = Modifier
                                .border(1.dp, BonyColors.Rule)
                                .clickable { viewModel.removeReactionEmoji(emoji) }
                                .padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(emoji, style = TextStyle(fontSize = 20.sp))
                            Spacer(Modifier.width(4.dp))
                            Text("✕", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { typed ->
                        // Only keep emojis from the keyboard; ignore letters.
                        input = extractEmojis(typed).joinToString("")
                    },
                    enabled = !full,
                    placeholder = {
                        Text(
                            text = if (full) "limit reached ($max)" else "tap, then pick emojis",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 20.sp, color = BonyColors.Text),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val canAdd = input.isNotBlank() && !full
                Text(
                    text = "ADD",
                    style = BonyType.tag.copy(
                        color = if (canAdd) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier
                        .border(1.dp, if (canAdd) BonyColors.AccentDim else BonyColors.Rule)
                        .clickable(enabled = canAdd) {
                            viewModel.addReactionEmojis(extractEmojis(input))
                            input = ""
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    emojis.size > 1 -> "tap like on any note to pick one of these"
                    emojis.size == 1 -> "with one emoji, like reacts with it directly · add more to get the picker"
                    else -> "with no emojis saved, like stays a heart"
                },
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}
