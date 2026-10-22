package social.tbone.ui.toolbox.webxdc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.webxdc.SavedXdcApp
import social.tbone.webxdc.XdcCustomTool

/**
 * Name + icon picker shown when a saved mini app is promoted to a toolbox tool.
 * The 20 choices come from [XdcToolIcons], the same outlined Material set the
 * built-in tools use.
 */
// ── Add-as-tool dialog ───────────────────────────────────────────────────────

@Composable
fun XdcAddToolDialog(
    app: SavedXdcApp,
    existing: XdcCustomTool?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, iconId: String) -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: app.name.lowercase().take(24)) }
    var iconId by remember { mutableStateOf(existing?.iconId ?: XdcToolIcons.all.first().id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BonyColors.Surface,
        title = {
            Text(
                "add as tool",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        },
        text = {
            Column {
                Text(
                    "give the toolbox entry a name and an icon",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    textStyle = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                Text("icon", style = BonyType.caption.copy(color = BonyColors.TextMute))
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    XdcToolIcons.all.chunked(5).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { choice ->
                                val selected = choice.id == iconId
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .appBorder(1.dp, if (selected) BonyColors.Accent else BonyColors.Rule)
                                        .background(if (selected) BonyColors.AccentBg else BonyColors.SurfaceAlt)
                                        .clickable { iconId = choice.id },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = choice.icon,
                                        contentDescription = choice.label,
                                        tint = if (selected) BonyColors.Accent else BonyColors.TextDim,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { app.name }, iconId) }) {
                Text("add tool", color = BonyColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel", color = BonyColors.TextMute)
            }
        },
    )
}
