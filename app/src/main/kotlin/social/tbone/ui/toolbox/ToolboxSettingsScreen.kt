package social.tbone.ui.toolbox

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.backup.EncryptedBackupViewModel
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.lock.PinSetupScreen
import social.tbone.ui.settings.BonyToggle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import java.time.LocalDate

/**
 * Toolbox settings — applies ONLY to the Toolbox:
 *  - Screenshot blocker (hides the toolbox screens from screenshots)
 *  - Toolbox PIN (locks the toolbox on open)
 *  - Toolbox duress PIN (wipes the toolbox)
 *  - Wipe toolbox now
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolboxSettingsScreen(
    onBack: () -> Unit,
    viewModel: ToolboxSettingsViewModel = hiltViewModel(),
) {

    val screenshotBlockEnabled by viewModel.screenshotBlockEnabled.collectAsStateWithLifecycle()
    val pinEnabled by viewModel.pinEnabled.collectAsStateWithLifecycle()
    val pinKeypadScrambled by viewModel.pinKeypadScrambled.collectAsStateWithLifecycle()
    val duressPinEnabled by viewModel.duressPinEnabled.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showPinSetup by remember { mutableStateOf(false) }
    var showDuressSetup by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    val backupViewModel: EncryptedBackupViewModel = hiltViewModel()
    val backupState by backupViewModel.uiState.collectAsStateWithLifecycle()
    var showExportPassphrase by remember { mutableStateOf(false) }
    var showImportPassphrase by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseConfirmation by remember { mutableStateOf("") }
    var selectedBackupUri by remember { mutableStateOf<Uri?>(null) }
    var exportPassphraseForPicker by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val secret = exportPassphraseForPicker
        exportPassphraseForPicker = null
        if (uri != null && secret != null) backupViewModel.export(uri, secret)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            selectedBackupUri = uri
            passphrase = ""
            showImportPassphrase = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "toolbox settings",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        error?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Danger.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(text = it, style = BonyType.meta.copy(color = BonyColors.Danger))
            }
        }

        // Screenshot blocker
        SectionHeader("PRIVACY")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("screenshot blocker", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    text = "hides the toolbox from screenshots (toolbox only)",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = screenshotBlockEnabled,
                enabled = true,
                onCheckedChange = viewModel::setScreenshotBlockEnabled,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // Toolbox PIN
        SectionHeader("LOCK")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (pinEnabled) viewModel.disablePin() else showPinSetup = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("toolbox pin", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    text = if (pinEnabled) "locks the toolbox on open · tap to disable"
                    else "4-digit pin that locks the toolbox",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = pinEnabled,
                enabled = true,
                onCheckedChange = { if (it) showPinSetup = true else viewModel.disablePin() },
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("scramble toolbox PIN keypad", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "randomize digit positions for each PIN entry",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = pinKeypadScrambled,
                enabled = true,
                onCheckedChange = viewModel::setPinKeypadScrambled,
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // Toolbox duress PIN
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (duressPinEnabled) viewModel.disableDuressPin() else showDuressSetup = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("duress pin", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    text = if (duressPinEnabled) "entering it on the toolbox lock wipes the toolbox"
                    else "wipes the toolbox instead of unlocking",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            BonyToggle(
                checked = duressPinEnabled,
                enabled = true,
                onCheckedChange = { if (it) showDuressSetup = true else viewModel.disableDuressPin() },
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // Encrypted notes and calendar backup
        SectionHeader("ENCRYPTED BACKUP")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "Passphrase-encrypted backups include notes, checklists, attachments, folders and calendar events. Import replaces those datasets only; other app data stays unchanged.",
                style = BonyType.meta.copy(color = BonyColors.TextDim),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Use a unique passphrase of at least 12 characters. It is not stored and cannot be recovered if forgotten.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupActionButton(
                    label = "↑ EXPORT",
                    enabled = !backupState.busy,
                    modifier = Modifier.weight(1f),
                ) { showExportPassphrase = true }
                BackupActionButton(
                    label = "↓ IMPORT",
                    enabled = !backupState.busy,
                    modifier = Modifier.weight(1f),
                ) { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
            }
            if (backupState.busy) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("working…", style = BonyType.meta.copy(color = BonyColors.TextMute))
                }
            }
            backupState.status?.let { status ->
                Text(
                    text = status,
                    style = BonyType.meta.copy(
                        color = if (backupState.isError) BonyColors.Danger else BonyColors.Accent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { backupViewModel.clearStatus() }
                        .padding(top = 10.dp),
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // Wipe now
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showWipeConfirm = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text("wipe toolbox now", style = BonyType.body.copy(color = BonyColors.Danger))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Spacer(Modifier.height(32.dp))
    }

    if (showPinSetup) {
        PinSetupScreen(
            onDone = { showPinSetup = false },
            onBack = { showPinSetup = false },
            isDuress = false,
            scrambleKeypad = pinKeypadScrambled,
            onSave = { pin -> viewModel.setPin(pin) { showPinSetup = false } },
        )
    }

    if (showDuressSetup) {
        PinSetupScreen(
            onDone = { showDuressSetup = false },
            onBack = { showDuressSetup = false },
            isDuress = true,
            scrambleKeypad = pinKeypadScrambled,
            onSave = { pin -> viewModel.setDuressPin(pin) { showDuressSetup = false } },
        )
    }

    if (showWipeConfirm) {
        AlertDialog(
            onDismissRequest = { showWipeConfirm = false },
            title = { Text("wipe toolbox?") },
            text = { Text("This clears encrypted notes, voice recordings, and toolbox settings. Accounts and relays are untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.wipeToolbox()
                    showWipeConfirm = false
                    onBack()
                }) { Text("WIPE", color = BonyColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirm = false }) { Text("cancel") }
            },
        )
    }

    if (showExportPassphrase) {
        AlertDialog(
            onDismissRequest = {
                showExportPassphrase = false
                passphrase = ""
                passphraseConfirmation = ""
            },
            title = { Text("Protect the backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose a passphrase you can keep separately. Anyone with the file and this passphrase can read its contents.",
                        style = BonyType.meta.copy(color = BonyColors.TextDim),
                    )
                    BackupPassphraseField("Passphrase", passphrase) { passphrase = it }
                    BackupPassphraseField("Confirm passphrase", passphraseConfirmation) { passphraseConfirmation = it }
                    if (passphrase.isNotEmpty() && passphrase.length < 12) {
                        Text("Use at least 12 characters.", style = BonyType.meta.copy(color = BonyColors.Danger))
                    } else if (passphraseConfirmation.isNotEmpty() && passphrase != passphraseConfirmation) {
                        Text("Passphrases do not match.", style = BonyType.meta.copy(color = BonyColors.Danger))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 12 && passphrase == passphraseConfirmation && !backupState.busy,
                    onClick = {
                        exportPassphraseForPicker = passphrase
                        passphrase = ""
                        passphraseConfirmation = ""
                        showExportPassphrase = false
                        exportLauncher.launch("tbone-backup-${LocalDate.now()}.tbbackup")
                    },
                ) { Text("Choose file") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportPassphrase = false
                    passphrase = ""
                    passphraseConfirmation = ""
                }) { Text("Cancel") }
            },
        )
    }

    if (showImportPassphrase && selectedBackupUri != null) {
        AlertDialog(
            onDismissRequest = {
                showImportPassphrase = false
                selectedBackupUri = null
                passphrase = ""
            },
            title = { Text("Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This replaces your current notes, checklists, attachments, folders and calendar events. Other app data is unchanged. If authentication or validation fails, the current vault is preserved.",
                        style = BonyType.meta.copy(color = BonyColors.TextDim),
                    )
                    BackupPassphraseField("Backup passphrase", passphrase) { passphrase = it }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.isNotBlank() && !backupState.busy,
                    onClick = {
                        val uri = selectedBackupUri
                        if (uri != null) backupViewModel.restore(uri, passphrase)
                        passphrase = ""
                        selectedBackupUri = null
                        showImportPassphrase = false
                    },
                ) { Text("Restore and replace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportPassphrase = false
                    selectedBackupUri = null
                    passphrase = ""
                }) { Text("Cancel") }
            },
        )
    }
}


@Composable
private fun BackupPassphraseField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BackupActionButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .appBorder(1.dp, if (enabled) BonyColors.AccentDim else BonyColors.Rule)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.tag.copy(color = if (enabled) BonyColors.Accent else BonyColors.TextMute),
        )
    }
}
