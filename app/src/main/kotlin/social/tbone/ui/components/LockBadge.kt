package social.tbone.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * The T-bone encryption badge — a real lock icon (not an emoji) plus a label,
 * styled to match the rest of the app. Used anywhere we want to say "this is
 * encrypted on device" without a cheap emoji.
 */
@Composable
fun LockBadge(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = BonyColors.TextMute,
    iconSize: Dp = 12.dp,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = "encrypted",
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = BonyType.caption.copy(color = tint),
        )
    }
}
