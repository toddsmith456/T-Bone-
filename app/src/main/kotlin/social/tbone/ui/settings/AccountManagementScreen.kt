package social.tbone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountManagementScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val hiddenMessage by viewModel.hiddenUnlockMessage.collectAsStateWithLifecycle()

    var showHideDialogFor by remember { mutableStateOf<String?>(null) }
    var hidePin by remember { mutableStateOf("") }
    var hiddenPinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            accounts.forEach { account ->
                val isActive = account.pubkey == activeAccount?.pubkey
                val name = account.displayName
                    ?: "${account.pubkey.take(8)}…${account.pubkey.takeLast(6)}"
                ListItem(
                    headlineContent = {
                        Text(
                            text = name,
                            fontFamily = if (account.displayName == null) FontFamily.Monospace else null,
                        )
                    },
                    supportingContent = {
                        Column {
                            if (isActive) Text("Active")
                            if (account.isHidden) Text("Hidden · PIN protected", style = BonyType.meta.copy(color = BonyColors.Warn))
                        }
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Hide / Unhide action
                            if (!account.isHidden) {
                                Text(
                                    "HIDE",
                                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                                    modifier = Modifier
                                        .appBorder(1.dp, BonyColors.Rule)
                                        .clickable { showHideDialogFor = account.pubkey }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Text(
                                    "UNHIDE",
                                    style = BonyType.tag.copy(color = BonyColors.Accent),
                                    modifier = Modifier
                                        .appBorder(1.dp, BonyColors.AccentDim)
                                        .clickable { viewModel.unhideAccount(account.pubkey) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            if (accounts.size > 1) {
                                IconButton(onClick = { viewModel.removeAccount(account.pubkey) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Remove account",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    },
                    modifier = if (!isActive) Modifier.clickable { viewModel.switchAccount(account.pubkey) } else Modifier,
                )
                HorizontalDivider()
            }

            ListItem(
                headlineContent = { Text("Add account") },
                supportingContent = { Text("Sign in with another Nostr identity") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onAddAccount),
            )
            HorizontalDivider()

            // ── Hidden account unlock ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .appBorder(1.dp, BonyColors.Rule)
                    .background(BonyColors.Surface)
                    .padding(12.dp),
            ) {
                Text("Hidden account", style = BonyType.caption.copy(color = BonyColors.Accent))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Enter your special PIN to reveal a hidden account. It will log you in and hide again when you switch away.",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hiddenPinInput,
                    onValueChange = { hiddenPinInput = it },
                    placeholder = { Text("special PIN", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, capitalization = KeyboardCapitalization.None),
                    textStyle = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .appBorder(1.dp, if (hiddenPinInput.isBlank()) BonyColors.Rule else BonyColors.AccentDim)
                        .clickable(enabled = hiddenPinInput.isNotBlank()) {
                            viewModel.unlockHiddenAccount(hiddenPinInput)
                            hiddenPinInput = ""
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("UNLOCK", style = BonyType.tag.copy(color = if (hiddenPinInput.isBlank()) BonyColors.TextMute else BonyColors.Accent))
                }
                hiddenMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = BonyType.meta.copy(color = if (it.contains("Wrong")) BonyColors.Danger else BonyColors.Accent))
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // Hide dialog — ask for PIN
    showHideDialogFor?.let { pubkey ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.85f))
                .clickable { showHideDialogFor = null },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .appBorder(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .clickable(enabled = false) {}
                    .padding(16.dp),
            ) {
                Text("Hide account", style = BonyType.body.copy(color = BonyColors.Text))
                Spacer(Modifier.height(6.dp))
                Text(
                    "Set a special PIN. This account will disappear from the list and only reappear when you enter this PIN. Switching away hides it again.",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = hidePin,
                    onValueChange = { hidePin = it },
                    placeholder = { Text("PIN (any length)", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    textStyle = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "cancel",
                        style = BonyType.tag.copy(color = BonyColors.TextMute),
                        modifier = Modifier.clickable { showHideDialogFor = null; hidePin = "" }.padding(10.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "HIDE",
                        style = BonyType.tag.copy(color = if (hidePin.isBlank()) BonyColors.TextMute else BonyColors.Accent),
                        modifier = Modifier
                            .appBorder(1.dp, if (hidePin.isBlank()) BonyColors.Rule else BonyColors.AccentDim)
                            .clickable(enabled = hidePin.isNotBlank()) {
                                viewModel.hideAccount(pubkey, hidePin)
                                showHideDialogFor = null
                                hidePin = ""
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
