package social.tbone.ui.toolbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.wallet.MoneroAddressPolicy
import social.tbone.wallet.WalletViewModel

@Composable
fun WalletToolScreen(
    onBack: () -> Unit,
    viewModel: WalletViewModel = hiltViewModel(),
) {
    val moneroEnabled by viewModel.moneroXapsEnabled.collectAsStateWithLifecycle()
    val lightningEnabled by viewModel.lightningZapsEnabled.collectAsStateWithLifecycle()
    val lightningAddress by viewModel.ownLightningAddress.collectAsStateWithLifecycle()
    val lightningStatus by viewModel.lightningStatus.collectAsStateWithLifecycle()
    val loadingLightning by viewModel.loadingLightning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val moneroAddress by viewModel.ownMoneroAddress.collectAsStateWithLifecycle()
    val hasOwnMoneroTarget by viewModel.hasOwnMoneroTarget.collectAsStateWithLifecycle()
    val ownAddressStatus by viewModel.ownAddressStatus.collectAsStateWithLifecycle()
    val loadingOwnAddress by viewModel.loadingOwnAddress.collectAsStateWithLifecycle()


    androidx.compose.runtime.LaunchedEffect(activePubkey) {
        val pubkey = activePubkey
        viewModel.loadLightningProfile(pubkey)
        if (pubkey == null) {
            viewModel.clearOwnMoneroTarget()
        } else {
            viewModel.loadOwnMoneroTarget(pubkey)
        }
    }

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
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
                item {
                    Text("Lightning zaps", style = BonyType.title.copy(color = BonyColors.Accent))
                    Text("Zaps open an invoice in your external wallet. No wallet connection or private key is stored.", style = BonyType.caption.copy(color = BonyColors.TextDim))
                }
                item {
                    Row(modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Show Lightning zap buttons", style = BonyType.body.copy(color = BonyColors.Text))
                            Text(if (lightningEnabled) "⚡ buttons shown on notes" else "⚡ buttons hidden", style = BonyType.caption.copy(color = BonyColors.TextMute))
                        }
                        Switch(checked = lightningEnabled, onCheckedChange = viewModel::setLightningZapsEnabled)
                    }
                }
                item {
                    Column(modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Your Lightning receiving address", style = BonyType.body.copy(color = BonyColors.Text))
                        Text("Publish your Lightning address in your public Nostr profile so clients can find it. Publishing or removing it changes the public profile; it does not change your Monero target.", style = BonyType.caption.copy(color = BonyColors.TextMute))
                        OutlinedTextField(value = lightningAddress, onValueChange = viewModel::setOwnLightningAddress,
                            modifier = Modifier.fillMaxWidth(), label = { Text("Lightning address or LNURL") },
                            placeholder = { Text("you@example.com") }, singleLine = true,
                            enabled = activePubkey != null && !loadingLightning,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None), colors = walletFieldColors())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { viewModel.saveLightningProfile() }, enabled = activePubkey != null && !loadingLightning && lightningAddress.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Accent, contentColor = Color.Black)) { Text("publish") }
                            Button(onClick = { viewModel.saveLightningProfile(remove = true) }, enabled = activePubkey != null && !loadingLightning && lightningAddress.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Surface, contentColor = BonyColors.Danger)) { Text("remove") }
                            if (loadingLightning) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = BonyColors.Accent)
                        }
                        Text(lightningStatus, style = BonyType.caption.copy(color = BonyColors.TextMute))
                        activePubkey?.let { pubkey ->
                            Text("reload current profile from relays", style = BonyType.caption.copy(color = BonyColors.Accent),
                                modifier = Modifier.clickable(enabled = !loadingLightning) { viewModel.loadLightningProfile(pubkey) }.padding(vertical = 2.dp))
                        }
                    }
                }
                item {
                    Text("Monero xaps", style = BonyType.title.copy(color = BonyColors.Accent))
                    Text(
                        "Xaps are direct Monero wallet hand-offs. Tapping ◉ looks up the author's public NIP-A3 address and opens a Monero wallet; the amount and final confirmation stay there.",
                        style = BonyType.caption.copy(color = BonyColors.TextDim),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Show Monero xap buttons", style = BonyType.body.copy(color = BonyColors.Text))
                            Text(
                                if (moneroEnabled) "◉ button shown on every note" else "◉ buttons are hidden",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                        Switch(checked = moneroEnabled, onCheckedChange = viewModel::setMoneroXapsEnabled)
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Your receiving address", style = BonyType.body.copy(color = BonyColors.Text))
                        Text(
                            "Publish a Monero address so other T-Bone users can xap you. The address is public Nostr data in a replaceable kind-10133 event; no seed or private key is requested or stored.",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                        OutlinedTextField(
                            value = moneroAddress,
                            onValueChange = viewModel::setOwnMoneroAddress,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Monero address") },
                            placeholder = { Text("4… or 8…") },
                            singleLine = true,
                            enabled = activePubkey != null && !busy && !loadingOwnAddress,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                capitalization = KeyboardCapitalization.None,
                            ),
                            colors = walletFieldColors(),
                        )
                        val plausibleAddress = MoneroAddressPolicy.normalizeOrNull(moneroAddress)
                        if (moneroAddress.isNotBlank() && plausibleAddress == null) {
                            Text(
                                "Check the address length and Monero base58 characters.",
                                style = BonyType.caption.copy(color = BonyColors.Warn),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = viewModel::publishOwnMoneroAddress,
                                enabled = activePubkey != null && !busy && !loadingOwnAddress && plausibleAddress != null,
                                colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Accent, contentColor = Color.Black),
                            ) {
                                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                else Text("publish")
                            }
                            if (moneroAddress.isNotBlank() || hasOwnMoneroTarget) {
                                Button(
                                    onClick = viewModel::removeOwnMoneroAddress,
                                    enabled = activePubkey != null && !busy && !loadingOwnAddress,
                                    colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Surface, contentColor = BonyColors.Danger),
                                ) { Text("remove") }
                            }
                            if (loadingOwnAddress) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                    color = BonyColors.Accent,
                                )
                            }
                        }
                        Text(
                            ownAddressStatus,
                            style = BonyType.caption.copy(color = if (ownAddressStatus.startsWith("Could not") || ownAddressStatus.startsWith("Showing") || ownAddressStatus.startsWith("Last saved")) BonyColors.Warn else BonyColors.TextMute),
                        )
                        activePubkey?.let { pubkey ->
                            Text(
                                "reload current target from relays",
                                style = BonyType.caption.copy(color = BonyColors.Accent),
                                modifier = Modifier.clickable(enabled = !loadingOwnAddress && !busy) {
                                    viewModel.loadOwnMoneroTarget(pubkey)
                                }.padding(vertical = 2.dp),
                            )
                        }
                        if (activePubkey == null) {
                            Text("An active Nostr account is required to publish a target.", style = BonyType.caption.copy(color = BonyColors.Warn))
                        }
                    }
                }

            message?.let { text ->
                item {
                    Text(
                        text,
                        style = BonyType.caption.copy(
                            color = if (isErrorMessage(text)) BonyColors.Danger else BonyColors.TextDim,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun walletFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BonyColors.Accent,
    unfocusedBorderColor = BonyColors.Rule,
    focusedLabelColor = BonyColors.Accent,
    unfocusedLabelColor = BonyColors.TextMute,
    focusedTextColor = BonyColors.Text,
    unfocusedTextColor = BonyColors.Text,
    cursorColor = BonyColors.Accent,
)

private fun isErrorMessage(message: String): Boolean =
    listOf("failed", "could not", "invalid", "must be", "no active", "rejected", "outside", "couldn't")
        .any { it in message.lowercase() }
