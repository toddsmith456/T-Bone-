package social.tbone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Avatar + display name + 3-word phrase sub-label.
 *
 * Used in NoteCard headers, account switcher rows, and anywhere a compact
 * identity representation is needed. Click on the avatar navigates to the profile.
 */
@Composable
fun IdentityRow(
    pubkeyHex: String,
    displayName: String?,
    avatarSize: Dp = 36.dp,
    onProfileClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val phrase = remember(pubkeyHex) {
        Phrase.wordsFor(pubkeyHex, 3).joinToString("·")
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(
            pubkeyHex = pubkeyHex,
            profile = null,
            size = avatarSize,
            modifier = if (onProfileClick != null)
                Modifier.clickable { onProfileClick(pubkeyHex) }
            else Modifier,
        )

        Spacer(Modifier.width(10.dp))

        Column {
            if (displayName != null) {
                Text(
                    text = displayName,
                    style = BonyType.bodyDim.copy(color = BonyColors.Text),
                    maxLines = 1,
                )
            }
            Text(
                text = phrase,
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                maxLines = 1,
            )
        }
    }
}
