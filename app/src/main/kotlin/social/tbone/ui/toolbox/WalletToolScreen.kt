package social.tbone.ui.toolbox

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
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val text by viewModel.connectionText.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val balance by viewModel.balanceMsats.collectAsStateWithLifecycle()
    val info by viewModel.walletInfo.collectAsStateWithLifecycle()
    val notifications by viewModel.zapNotifications.collectAsStateWithLifecycle()

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
                    "Connect a wallet with Nostr Wallet Connect. The connection secret is protected by Android Keystore and is never shown after saving.",
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
                            if (enabled) "Zap buttons are available · 21 sats per tap" else "Zap buttons are hidden",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = viewModel::setZapsEnabled)
                }
            }
            item {
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
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::connect,
                        enabled = !busy && text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BonyColors.Accent,
                            contentColor = Color.Black,
                        ),
                    ) {
                        if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                        else Text("validate & connect")
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
                    "Only Nostr zap and payment activity is shown here. Other wallet transactions stay out of this view.",
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
