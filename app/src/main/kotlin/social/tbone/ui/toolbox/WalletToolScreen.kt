package social.tbone.ui.toolbox

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
    val lightningEnabled by viewModel.lightningZapsEnabled.collectAsStateWithLifecycle()
    val moneroEnabled by viewModel.moneroXapsEnabled.collectAsStateWithLifecycle()
    val zapAmount by viewModel.zapAmountSats.collectAsStateWithLifecycle()
    val zapAmounts by viewModel.zapAmounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val moneroAddress by viewModel.ownMoneroAddress.collectAsStateWithLifecycle()
    val hasOwnMoneroTarget by viewModel.hasOwnMoneroTarget.collectAsStateWithLifecycle()
    val ownAddressStatus by viewModel.ownAddressStatus.collectAsStateWithLifecycle()
    val loadingOwnAddress by viewModel.loadingOwnAddress.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var zapAmountText by remember(zapAmount) { mutableStateOf(zapAmount.toString()) }
    var newPresetText by remember { mutableStateOf("") }
    var presetsExpanded by rememberSaveable { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(activePubkey) {
        val pubkey = activePubkey
        if (pubkey == null) viewModel.clearOwnMoneroTarget()
        else viewModel.loadOwnMoneroTarget(pubkey)
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
        WalletRailTabs(selectedTab = selectedTab, onSelected = { selectedTab = it })

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectedTab == 0) {
                item {
                    Text("⚡ Lightning", style = BonyType.title.copy(color = BonyColors.Accent))
                    Text(
                        "T-Bone requests a NIP-57 invoice, checks its amount, then opens it in an installed Lightning wallet. You confirm and pay there; T-Bone never receives payment credentials or marks an invoice as paid.",
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
                            Text("Lightning zaps", style = BonyType.body.copy(color = BonyColors.Text))
                            Text(
                                if (lightningEnabled) "Zap buttons are shown · $zapAmount sats default" else "Zap buttons are hidden",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                        Switch(checked = lightningEnabled, onCheckedChange = viewModel::setLightningZapsEnabled)
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp),
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
                            Text(if (presetsExpanded) "⌃" else "⌄", style = BonyType.body.copy(color = BonyColors.Accent))
                        }
                        AnimatedVisibility(visible = presetsExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Set any amount from 1 to 1,000,000 sats. Presets appear after tapping a note's lightning button and can be edited, reordered, or removed.",
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
                                        colors = walletFieldColors(),
                                    )
                                    Button(
                                        onClick = { viewModel.saveZapAmount(zapAmountText) },
                                        enabled = !busy,
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
                                            colors = walletFieldColors(),
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
                                        colors = walletFieldColors(),
                                    )
                                    Button(
                                        onClick = { viewModel.addPreset(newPresetText); newPresetText = "" },
                                        enabled = !busy,
                                        colors = ButtonDefaults.buttonColors(containerColor = BonyColors.Surface, contentColor = BonyColors.Accent),
                                    ) { Text("add") }
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Text("Monero xaps", style = BonyType.title.copy(color = BonyColors.Accent))
                    Text(
                        "Xaps are direct Monero wallet hand-offs, not Lightning zaps. Tapping a note's M button looks up the author's public NIP-A3 address and opens a Monero wallet; the amount and final confirmation stay there.",
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
                                if (moneroEnabled) "M button shown on every note" else "M buttons are hidden",
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
private fun WalletRailTabs(selectedTab: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)
            .appBorder(1.dp, BonyColors.Rule).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("⚡ LIGHTNING", "◉ MONERO").forEachIndexed { index, label ->
            Box(
                modifier = Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selectedTab == index) BonyColors.Surface else BonyColors.Bg)
                    .clickable { onSelected(index) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = BonyType.caption.copy(color = if (selectedTab == index) BonyColors.Accent else BonyColors.TextMute),
                )
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
