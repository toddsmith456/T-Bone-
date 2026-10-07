package social.tbone.ui.toolbox

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.wallet.NwcConnectionState
import social.tbone.wallet.NwcWalletViewModel

@Composable
fun WalletToolScreen(
    onBack: () -> Unit,
    viewModel: NwcWalletViewModel = hiltViewModel(),
) {
    val enabled by viewModel.zapsEnabled.collectAsStateWithLifecycle()
    val zapAmount by viewModel.zapAmountSats.collectAsStateWithLifecycle()
    val zapAmounts by viewModel.zapAmounts.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val text by viewModel.connectionText.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val balance by viewModel.balanceMsats.collectAsStateWithLifecycle()
    val info by viewModel.walletInfo.collectAsStateWithLifecycle()
    val notifications by viewModel.zapNotifications.collectAsStateWithLifecycle()
    var zapAmountText by remember(zapAmount) { mutableStateOf(zapAmount.toString()) }
    var newPresetText by remember { mutableStateOf("") }
    // Keep the space-heavy editors closed so live wallet activity has room on
    // smaller phones; both sections remain one tap away.
    var presetsExpanded by rememberSaveable { mutableStateOf(false) }
    var connectionExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().background(BonyColors.Bg).statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { onBack() },
            )
            Spacer(Modifier.width(10.dp))
            Text("wallet tool", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Divider(color = BonyColors.Rule, thickness = 1.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("⚡ zaps", style = BonyType.title.copy(color = BonyColors.Accent))
                Text(
                    "Connect any NWC-compatible Lightning wallet. Minibits is the test wallet for this implementation. The connection secret is protected by Android Keystore and is never shown after saving.",
                    style = BonyType.caption.copy(color = BonyColors.TextDim),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().border(1.dp, BonyColors.Rule).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Zaps", style = BonyType.body.copy(color = BonyColors.Text))
                        Text(
                            if (enabled) "Zap buttons are available · $zapAmount sats default" else "Zap buttons are hidden",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = viewModel::setZapsEnabled)
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().border(1.dp, BonyColors.Rule).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { presetsExpanded = !presetsExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("zap amounts", style = BonyType.body.copy(color = BonyColors.Text))
                            Text(
                                "$zapAmount sats default · ${zapAmounts.size} preset${if (zapAmounts.size == 1) "" else "s"}",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                        Text(
                            if (presetsExpanded) "⌃" else "⌄",
                            style = BonyType.body.copy(color = BonyColors.Accent),
                        )
                    }
                    AnimatedVisibility(visible = presetsExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Set any amount from 1 to 1,000,000 sats. Presets are shown after tapping the lightning button and can be edited, reordered, or removed.",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = zapAmountText,
                                    onValueChange = { zapAmountText = it.filter(Char::isDigit) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("default sats") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BonyColors.Accent,
                                        unfocusedBorderColor = BonyColors.Rule,
                                        focusedLabelColor = BonyColors.Accent,
                                        unfocusedLabelColor = BonyColors.TextMute,
                                        focusedTextColor = BonyColors.Text,
                                        unfocusedTextColor = BonyColors.Text,
                                        cursorColor = BonyColors.Accent,
                                    ),
                                )
                                Button(
                                    onClick = { viewModel.saveZapAmount(zapAmountText) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Accent, contentColor = Color.Black),
                                ) { Text("save") }
                            }
                            zapAmounts.forEachIndexed { index, preset ->
                                var presetText by remember(preset) { mutableStateOf(preset.toString()) }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    OutlinedTextField(
                                        value = presetText,
                                        onValueChange = { presetText = it.filter(Char::isDigit) },
                                        modifier = Modifier.weight(1f),
                                        label = { Text("preset ${index + 1} · sats") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = BonyColors.Accent,
                                            unfocusedBorderColor = BonyColors.Rule,
                                            focusedLabelColor = BonyColors.Accent,
                                            unfocusedLabelColor = BonyColors.TextMute,
                                            focusedTextColor = BonyColors.Text,
                                            unfocusedTextColor = BonyColors.Text,
                                            cursorColor = BonyColors.Accent,
                                        ),
                                    )
                                    Text("save", style = BonyType.caption.copy(color = BonyColors.Accent), modifier = Modifier.clickable { viewModel.savePreset(index, presetText) }.padding(6.dp))
                                    Text("↑", style = BonyType.body.copy(color = if (index > 0) BonyColors.Accent else BonyColors.TextMute), modifier = Modifier.clickable(enabled = index > 0) { viewModel.movePreset(index, index - 1) }.padding(6.dp))
                                    Text("↓", style = BonyType.body.copy(color = if (index < zapAmounts.lastIndex) BonyColors.Accent else BonyColors.TextMute), modifier = Modifier.clickable(enabled = index < zapAmounts.lastIndex) { viewModel.movePreset(index, index + 1) }.padding(6.dp))
                                    Text("×", style = BonyType.body.copy(color = if (zapAmounts.size > 1) BonyColors.Danger else BonyColors.TextMute), modifier = Modifier.clickable { viewModel.removePreset(preset) }.padding(6.dp))
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newPresetText,
                                    onValueChange = { newPresetText = it.filter(Char::isDigit) },
                                    modifier = Modifier.weight(1f),
                                    label = { Text("new preset · sats") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BonyColors.Accent,
                                        unfocusedBorderColor = BonyColors.Rule,
                                        focusedLabelColor = BonyColors.Accent,
                                        unfocusedLabelColor = BonyColors.TextMute,
                                        focusedTextColor = BonyColors.Text,
                                        unfocusedTextColor = BonyColors.Text,
                                        cursorColor = BonyColors.Accent,
                                    ),
                                )
                                Button(
                                    onClick = { viewModel.addPreset(newPresetText); newPresetText = "" },
                                    colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Surface, contentColor = BonyColors.Accent),
                                ) { Text("add") }
                            }
                        }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().border(1.dp, BonyColors.Rule).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { connectionExpanded = !connectionExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("NWC connection", style = BonyType.body.copy(color = BonyColors.Text))
                            Text(
                                when (state) {
                                    NwcConnectionState.READY -> "connected · wallet relay notifications are live"
                                    NwcConnectionState.CONNECTING -> "connecting…"
                                    NwcConnectionState.DISCONNECTED -> "disconnected · tap to reconnect"
                                    NwcConnectionState.NOT_CONFIGURED -> "not configured"
                                    NwcConnectionState.ERROR -> "needs attention"
                                },
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                        Text(
                            if (connectionExpanded) "⌃" else "⌄",
                            style = BonyType.body.copy(color = BonyColors.Accent),
                        )
                    }
                    AnimatedVisibility(visible = connectionExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = viewModel::setConnectionText,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("NWC connection address") },
                                placeholder = { Text("nostr+walletconnect://…") },
                                singleLine = false,
                                minLines = 2,
                                maxLines = 4,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                enabled = !busy,
                                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BonyColors.Accent,
                                    unfocusedBorderColor = BonyColors.Rule,
                                    focusedLabelColor = BonyColors.Accent,
                                    unfocusedLabelColor = BonyColors.TextMute,
                                    focusedTextColor = BonyColors.Text,
                                    unfocusedTextColor = BonyColors.Text,
                                    cursorColor = BonyColors.Accent,
                                ),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = viewModel::connect,
                                    enabled = !busy && text.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BonyColors.Accent,
                                        contentColor = Color.Black,
                                    ),
                                ) {
                                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    else Text("validate & connect")
                                }
                                if (state == NwcConnectionState.DISCONNECTED) {
                                    Button(
                                        onClick = viewModel::reconnect,
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Accent, contentColor = Color.Black),
                                    ) { Text("reconnect") }
                                }
                                if (state == NwcConnectionState.READY || state == NwcConnectionState.CONNECTING) {
                                    Button(
                                        onClick = viewModel::disconnect,
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Surface, contentColor = BonyColors.Warn),
                                    ) { Text("disconnect") }
                                }
                                if (state != NwcConnectionState.NOT_CONFIGURED) {
                                    Button(
                                        onClick = viewModel::removeConnection,
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BonyColors.Surface,
                                            contentColor = BonyColors.Danger,
                                        ),
                                    ) { Text("remove") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val statusColor = when (state) {
                    NwcConnectionState.READY -> BonyColors.Accent
                    NwcConnectionState.ERROR -> BonyColors.Danger
                    NwcConnectionState.CONNECTING -> BonyColors.Warn
                    else -> BonyColors.TextMute
                }
                Text(status, style = BonyType.caption.copy(color = statusColor))
                if (info != null || balance != null) {
                    Text(
                        listOfNotNull(
                            info?.alias,
                            info?.network,
                            balance?.let { "${it / 1000} sats available" },
                        ).joinToString(" · "),
                        style = BonyType.caption.copy(color = BonyColors.TextDim),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "refresh",
                        style = BonyType.caption.copy(color = BonyColors.Accent),
                        modifier = Modifier.clickable { viewModel.refresh() }.padding(top = 4.dp),
                    )
                }
                message?.let {
                    Text(
                        it,
                        style = BonyType.caption.copy(color = if (it.contains("failed", true) || it.contains("could not", true)) BonyColors.Danger else BonyColors.TextDim),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item {
                Text("zap notifications", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "Nostr zap payments arrive from the connected wallet relay. This session-only list is never cached; other wallet transactions stay out.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (notifications.isEmpty()) {
                item {
                    Text("no zaps yet", style = BonyType.caption.copy(color = BonyColors.TextMute))
                }
            } else {
                items(notifications, key = { it.id }) { notification ->
                    Column(
                        modifier = Modifier.fillMaxWidth().border(1.dp, BonyColors.Rule).padding(10.dp),
                    ) {
                        Text(
                            "${if (notification.direction == "sent") "↑" else "↓"} ${notification.amountMsats / 1000} sats",
                            style = BonyType.body.copy(color = BonyColors.Accent),
                        )
                        Text(
                            notification.description,
                            style = BonyType.caption.copy(color = BonyColors.TextDim),
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
