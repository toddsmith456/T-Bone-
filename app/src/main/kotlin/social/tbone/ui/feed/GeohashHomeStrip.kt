package social.tbone.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.toolbox.geohash.GeohashHomeViewModel

/**
 * The compact, horizontally-scrollable row of home-pinned location channels.
 * Each is a small circle avatar + code with a red unread badge. Tapping opens
 * the channel and clears its badge. An unlimited number fit — it scrolls.
 */
@Composable
fun GeohashHomeStrip(
    onOpenGeohash: (String) -> Unit,
    viewModel: GeohashHomeViewModel = hiltViewModel(),
) {
    val channels by viewModel.homeChannels.collectAsStateWithLifecycle()
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    if (channels.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        channels.forEach { channel ->
            val count = unread[channel.code] ?: 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(end = 12.dp)
                    .clickable {
                        viewModel.markSeen(channel.code)
                        onOpenGeohash(channel.code)
                    },
            ) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(BonyColors.AccentBg, CircleShape)
                            .border(1.dp, BonyColors.AccentDim, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = channel.code.take(1).uppercase(),
                            style = BonyType.body.copy(color = BonyColors.Accent),
                        )
                    }
                    if (count > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .background(BonyColors.Danger, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (count > 99) "99+" else "$count",
                                style = BonyType.caption.copy(color = BonyColors.Bg),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(0.dp))
                Text(
                    text = channel.code,
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().size(0.dp))
}
