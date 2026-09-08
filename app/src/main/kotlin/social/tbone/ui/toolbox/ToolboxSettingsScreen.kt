package social.tbone.ui.toolbox

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.components.SectionHeader
import social.tbone.ui.lock.PinSetupScreen
import social.tbone.ui.settings.BonyToggle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

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
    val duressPinEnabled by viewModel.duressPinEnabled.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    var showPinSetup by remember { mutableStateOf(false) }
    var showDuressSetup by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

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
            onSave = { pin -> viewModel.setPin(pin) { showPinSetup = false } },
        )
    }

    if (showDuressSetup) {
        PinSetupScreen(
            onDone = { showDuressSetup = false },
            onBack = { showDuressSetup = false },
            isDuress = true,
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
}
