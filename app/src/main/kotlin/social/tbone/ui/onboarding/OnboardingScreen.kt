package social.tbone.ui.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

@Composable
fun OnboardingScreen(
    onAccountAdded: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(uiState.success) { if (uiState.success) onAccountAdded() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { snackbarHostState.showSnackbar(it); viewModel.clearError() }
    }

    BackHandler(enabled = uiState.showNsecBunkerForm) { viewModel.hideNsecBunkerForm() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 32.dp),
        ) {
            if (uiState.showNsecBunkerForm) {
                NsecBunkerForm(
                    isLoading = uiState.isLoading,
                    onConnect = viewModel::addAccountWithNsecBunker,
                    onBack = viewModel::hideNsecBunkerForm,
                )
            } else if (uiState.isLoading) {
                AmberHandoffView()
            } else {
                MethodPicker(
                    onAmberClick = { viewModel.addAccountWithAmber(context.packageName) },
                    onNsecBunkerClick = viewModel::showNsecBunkerForm,
                    onLocalKeyClick = viewModel::addLocalKeyAccount,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ── Method picker ─────────────────────────────────────────────────────────────

@Composable
private fun MethodPicker(
    onAmberClick: () -> Unit,
    onNsecBunkerClick: () -> Unit,
    onLocalKeyClick: () -> Unit,
) {
    // Wordmark — no version tag
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("t-bone", style = BonyType.wordmark.copy(color = BonyColors.Text))
    }

    Spacer(Modifier.height(24.dp))

    // Manifesto block
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.Surface)
            .padding(14.dp),
    ) {
        Text("> sign in", style = BonyType.body.copy(color = BonyColors.TextDim))
        Text(
            "your private key never touches this app.",
            style = BonyType.body.copy(color = BonyColors.TextDim),
        )
        Text(
            "pick how you want to sign events.",
            style = BonyType.body.copy(color = BonyColors.TextDim),
        )
    }

    Spacer(Modifier.height(24.dp))

    // Amber — recommended
    SignerCard(
        name = "Amber",
        nipTag = "NIP-55",
        description = "Delegates signing to the Amber signer app via Android intents.",
        keyFooter = "key never leaves signer",
        keyFooterDanger = false,
        recommended = true,
        onClick = onAmberClick,
    )

    Spacer(Modifier.height(10.dp))

    SignerCard(
        name = "nsecBunker",
        nipTag = "NIP-46",
        description = "Connects to a remote bunker over a Nostr relay. Key stays on the bunker.",
        keyFooter = "key never leaves signer",
        keyFooterDanger = false,
        recommended = false,
        onClick = onNsecBunkerClick,
    )

    Spacer(Modifier.height(10.dp))

    SignerCard(
        name = "Local key",
        nipTag = "NIP-06",
        description = "Generates and stores a key in Android Keystore. Last resort — use a signer app.",
        keyFooter = "! key stored on device",
        keyFooterDanger = true,
        recommended = false,
        onClick = onLocalKeyClick,
    )

    Spacer(Modifier.height(32.dp))

    Text(
        text = "no analytics · no google · no tracking",
        style = BonyType.caption.copy(color = BonyColors.TextMute),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SignerCard(
    name: String,
    nipTag: String,
    description: String,
    keyFooter: String,
    keyFooterDanger: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (recommended) BonyColors.Accent else BonyColors.Rule)
            .background(if (recommended) BonyColors.AccentBg else BonyColors.Surface)
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = BonyType.body.copy(color = BonyColors.Text), modifier = Modifier.weight(1f))
                BonyTag(nipTag, color = BonyColors.TextMute)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(BonyColors.Rule),
            )

            Text(description, style = BonyType.bodyDim.copy(color = BonyColors.TextDim))

            Spacer(Modifier.height(8.dp))

            Text(
                text = keyFooter,
                style = BonyType.meta.copy(
                    color = if (keyFooterDanger) BonyColors.Warn else BonyColors.TextMute,
                ),
            )
        }

        // RECOMMENDED corner ribbon
        if (recommended) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(BonyColors.Accent)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("RECOMMENDED", style = BonyType.tag.copy(color = BonyColors.Bg))
            }
        }
    }
}

// ── Amber handoff view ────────────────────────────────────────────────────────

@Composable
private fun AmberHandoffView() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
        Text("← amber.handoff", style = BonyType.body.copy(color = BonyColors.Text))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BonyColors.Accent)
            .background(BonyColors.AccentBg)
            .padding(16.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(14.dp)
                        .background(BonyColors.Accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "AWAITING SIGNER",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                )
            }

            Spacer(Modifier.height(10.dp))

            Text(
                "Approve the public-key request in Amber.",
                style = BonyType.body.copy(color = BonyColors.TextDim),
            )

            Spacer(Modifier.height(12.dp))

            // Progress bar (12 segments: 7 filled, 5 empty)
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                repeat(12) { i ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .background(if (i < 7) BonyColors.Accent else BonyColors.Rule),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text("> requested: get_public_key", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Text("> permissions: sign_event, nip04, nip44", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Text("> timeout: 30s", style = BonyType.meta.copy(color = BonyColors.TextMute))
        }
    }

    Spacer(Modifier.height(12.dp))

    CircularProgressIndicator(
        modifier = Modifier.size(24.dp),
        color = BonyColors.Accent,
        strokeWidth = 1.dp,
    )
}

// ── nsecBunker form ────────────────────────────────────────────────────────────

@Composable
private fun NsecBunkerForm(
    isLoading: Boolean,
    onConnect: (String) -> Unit,
    onBack: () -> Unit,
) {
    var bunkerUrl by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier.padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "←",
            style = BonyType.body.copy(color = BonyColors.TextMute),
            modifier = Modifier.clickable { onBack() },
        )
        Spacer(Modifier.width(8.dp))
        Text("nsecBunker", style = BonyType.title.copy(color = BonyColors.Text))
    }

    Text(
        "Paste the bunker:// URI from your bunker app.",
        style = BonyType.body.copy(color = BonyColors.TextDim),
    )

    Spacer(Modifier.height(20.dp))

    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = BonyColors.Accent,
            strokeWidth = 1.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text("connecting to bunker…", style = BonyType.meta.copy(color = BonyColors.TextMute))
    } else {
        OutlinedTextField(
            value = bunkerUrl,
            onValueChange = { bunkerUrl = it },
            label = { Text("bunker:// URL", style = BonyType.meta) },
            placeholder = { Text("bunker://pubkey?relay=wss://…", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (bunkerUrl.isNotBlank()) onConnect(bunkerUrl) }),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BonyColors.Accent,
                unfocusedBorderColor = BonyColors.Rule,
                focusedTextColor = BonyColors.Text,
                unfocusedTextColor = BonyColors.Text,
                cursorColor = BonyColors.Accent,
            ),
            textStyle = BonyType.body,
        )

        Spacer(Modifier.height(12.dp))

        BonyButton(
            label = "CONNECT",
            primary = true,
            enabled = bunkerUrl.isNotBlank(),
            onClick = { onConnect(bunkerUrl) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        BonyButton(
            label = "BACK",
            primary = false,
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Shared UI atoms ───────────────────────────────────────────────────────────

@Composable
fun BonyTag(label: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.4f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = BonyType.tag.copy(color = color))
    }
}

@Composable
fun BonyButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (primary) BonyColors.Accent else BonyColors.Bg
    val fg = if (primary) BonyColors.Bg else BonyColors.Text
    val borderColor = if (primary) BonyColors.Accent else BonyColors.Rule
    val disabledAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .border(1.dp, borderColor.copy(alpha = disabledAlpha))
            .background(bg.copy(alpha = disabledAlpha))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.button.copy(color = fg.copy(alpha = disabledAlpha)),
        )
    }
}
