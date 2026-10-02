package social.tbone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/** The five entries of the app's bottom navigation bar. */
enum class BottomTab(val label: String, val icon: ImageVector) {
    HOME("HOME", Icons.Outlined.Home),
    COMPOSE("WRITE", Icons.Outlined.Edit),
    NOTIF("NOTIF", Icons.Filled.Notifications),
    TOOLS("TOOLS", Icons.Outlined.Construction),
    CONF("CONF", Icons.Outlined.Settings),
}

/**
 * The bottom navigation bar.
 *
 * Shared so every top-level screen shows exactly the same bar as the home tab
 * (previously the bar lived inside the feed screen, so the toolbox and
 * notifications screens dropped it and only offered a back arrow).
 */
@Composable
fun BonyBottomBar(
    selected: BottomTab?,
    onSelect: (BottomTab) -> Unit,
    hasNotifications: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BonyColors.Surface),
    ) {
        // Top hairline
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            BottomTab.entries.forEach { tab ->
                val isActive = tab == selected
                val contentColor = if (isActive) BonyColors.Accent else BonyColors.TextMute

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(tab) }
                        .then(
                            if (isActive) Modifier.border(
                                width = 1.dp,
                                color = BonyColors.Accent,
                                shape = RectangleShape,
                            ) else Modifier,
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                        // Bell tab shows a dot when there are unread notifications.
                        if (hasNotifications && tab == BottomTab.NOTIF) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(6.dp)
                                    .background(BonyColors.Accent, CircleShape),
                            )
                        }
                    }
                    Text(text = tab.label, style = BonyType.caption.copy(color = contentColor))
                }
            }
        }
    }
}
