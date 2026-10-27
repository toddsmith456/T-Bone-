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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

/**
 * Compact auto-lock timeout selector: shows current minutes and opens a
 * centered popup with a 1–30 grid. Used for both app-wide and toolbox PIN.
 */
@Composable
fun AutoLockMinutesRow(
    currentMinutes: Int,
    onSelect: (Int) -> Unit,
    title: String = "auto-lock timeout",
    description: String = "PIN required again after this many minutes",
) {
    var showPopup by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPopup = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BonyType.body.copy(color = BonyColors.Text))
            Text(
                text = description,
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
        Box(
            modifier = Modifier
                .appBorder(1.dp, BonyColors.Rule)
                .background(BonyColors.SurfaceAlt)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = "$currentMinutes min",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

    if (showPopup) {
        AutoLockMinutesPopup(
            currentMinutes = currentMinutes,
            onSelect = {
                onSelect(it)
                showPopup = false
            },
            onDismiss = { showPopup = false },
        )
    }
}

@Composable
private fun AutoLockMinutesPopup(
    currentMinutes: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .appBorder(1.dp, BonyColors.Rule)
                .background(BonyColors.Surface)
                .padding(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "auto-lock timeout",
                    style = BonyType.body.copy(color = BonyColors.Text),
                )
                Text(
                    text = "choose 1 – 30 minutes",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items((1..30).toList()) { m ->
                        val selected = m == currentMinutes
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (selected) BonyColors.Accent else BonyColors.SurfaceAlt,
                                )
                                .border(
                                    1.dp,
                                    if (selected) BonyColors.Accent else BonyColors.Rule,
                                    RoundedCornerShape(4.dp),
                                )
                                .clickable { onSelect(m) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "$m",
                                style = BonyType.body.copy(
                                    color = if (selected) BonyColors.Bg else BonyColors.Text,
                                ),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.Rule)
                            .clickable { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("close", style = BonyType.caption.copy(color = BonyColors.TextMute))
                    }
                }
            }
        }
    }
}
