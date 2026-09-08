package social.tbone.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import social.tbone.ui.lock.PinKeypad
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Content filters:
 *  - Hide NSFW (notes with a NIP-36 "content-warning" tag).
 *  - Bleep words (replaced with ****).
 *  - Hide words (notes containing them disappear entirely).
 *
 * A parental PIN can lock these settings: when set, the screen opens locked
 * and every control stays behind the pin until the correct 4-digit pin is
 * entered (session unlock). A parent configures filters, sets the pin, and a
 * child can't change them.
 */
@Composable
fun ContentFiltersScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val hideNsfw by viewModel.hideNsfw.collectAsStateWithLifecycle()
    val bleepWords by viewModel.bleepWords.collectAsStateWithLifecycle()
    val hideWords by viewModel.hideWords.collectAsStateWithLifecycle()
    val parentalEnabled by viewModel.parentalPinEnabled.collectAsStateWithLifecycle()
    val filtersUnlocked by viewModel.filtersUnlocked.collectAsStateWithLifecycle()

    var showSetPin by remember { mutableStateOf(false) }

    // Locked: the whole screen is a pin entry — the filters themselves are
    // never composed, so a child can't even see (let alone change) them.
    if (parentalEnabled && !filtersUnlocked) {
        ParentalPinLock(onBack = onBack, viewModel = viewModel)
        return
    }

    var bleepInput by remember { mutableStateOf("") }
    var hideInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
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
            Text("content filters", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        if (showSetPin) {
            ParentalPinSetup(
                onCancel = { showSetPin = false },
                viewModel = viewModel,
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ── NSFW toggle ────────────────────────────────────────────────────
            item {
                FilterSection("nsfw") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "hide notes labeled nsfw",
                            style = BonyType.meta.copy(color = BonyColors.Text),
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .size(36.dp, 22.dp)
                                .background(if (hideNsfw) BonyColors.AccentBg else BonyColors.Bg)
                                .border(1.dp, if (hideNsfw) BonyColors.Accent else BonyColors.RuleStrong)
                                .clickable { viewModel.toggleHideNsfw() }
                                .padding(2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(if (hideNsfw) Alignment.CenterEnd else Alignment.CenterStart)
                                    .background(if (hideNsfw) BonyColors.Accent else BonyColors.TextMute),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "hides notes with a NIP-36 content-warning tag (nsfw / sensitive content)",
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                }
            }

            // ── Bleep words ────────────────────────────────────────────────────
            item {
                FilterSection("bleep words") {
                    Text(
                        text = "these words are replaced with asterisks in notes (the note still shows)",
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = bleepInput,
                            onValueChange = { bleepInput = it },
                            placeholder = { Text("add word", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                            singleLine = true,
                            textStyle = BonyType.body.copy(color = BonyColors.Text),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "ADD",
                            style = BonyType.tag.copy(
                                color = if (bleepInput.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                            ),
                            modifier = Modifier
                                .border(1.dp, if (bleepInput.isNotBlank()) BonyColors.AccentDim else BonyColors.Rule)
                                .clickable(enabled = bleepInput.isNotBlank()) {
                                    viewModel.addBleepWord(bleepInput)
                                    bleepInput = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    if (bleepWords.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FilterChips(bleepWords.toList().sorted()) { viewModel.removeBleepWord(it) }
                    }
                }
            }

            // ── Hide words ─────────────────────────────────────────────────────
            item {
                FilterSection("hide words") {
                    Text(
                        text = "any note containing these words is hidden entirely",
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = hideInput,
                            onValueChange = { hideInput = it },
                            placeholder = { Text("add word", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                            singleLine = true,
                            textStyle = BonyType.body.copy(color = BonyColors.Text),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "ADD",
                            style = BonyType.tag.copy(
                                color = if (hideInput.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                            ),
                            modifier = Modifier
                                .border(1.dp, if (hideInput.isNotBlank()) BonyColors.AccentDim else BonyColors.Rule)
                                .clickable(enabled = hideInput.isNotBlank()) {
                                    viewModel.addHideWord(hideInput)
                                    hideInput = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                    if (hideWords.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FilterChips(hideWords.toList().sorted()) { viewModel.removeHideWord(it) }
                    }
                }
            }

            // ── Parental pin ───────────────────────────────────────────────────
            item {
                FilterSection("parental pin") {
                    if (parentalEnabled) {
                        Text(
                            text = "these filter settings are locked behind a pin — a child can't change them",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (filtersUnlocked) "pin: on · unlocked" else "pin: on · locked",
                                style = BonyType.meta.copy(
                                    color = if (filtersUnlocked) BonyColors.Accent else BonyColors.TextMute,
                                ),
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "LOCK",
                                style = BonyType.tag.copy(
                                    color = if (filtersUnlocked) BonyColors.Accent else BonyColors.TextMute,
                                ),
                                modifier = Modifier
                                    .border(1.dp, BonyColors.Rule)
                                    .clickable(enabled = filtersUnlocked) { viewModel.relockFilters() }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "REMOVE",
                                style = BonyType.tag.copy(color = BonyColors.Danger),
                                modifier = Modifier
                                    .border(1.dp, BonyColors.Danger)
                                    .clickable { viewModel.disableParentalPin() }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "not set — filters are freely editable. Set a pin to lock them.",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "SET PIN",
                            style = BonyType.tag.copy(color = BonyColors.Accent),
                            modifier = Modifier
                                .border(1.dp, BonyColors.AccentDim)
                                .clickable { showSetPin = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

// ── Parental pin: lock entry ─────────────────────────────────────────────────

@Composable
private fun ParentalPinLock(
    onBack: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.length == 4) {
            val ok = viewModel.verifyParentalPin(pin)
            if (ok) {
                viewModel.unlockFilters()
            } else {
                error = true
                pin = ""
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
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
            Text("content filters", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "🔒",
                style = BonyType.title.copy(color = BonyColors.Accent),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "filters locked",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (error) "wrong pin — try again" else "enter parental pin to change filters",
                style = BonyType.meta.copy(
                    color = if (error) BonyColors.Danger else BonyColors.TextMute,
                ),
            )

            Spacer(Modifier.height(28.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .border(
                                1.dp,
                                if (filled) (if (error) BonyColors.Danger else BonyColors.Accent) else BonyColors.Rule,
                            )
                            .background(if (filled) (if (error) BonyColors.Danger else BonyColors.Accent) else BonyColors.Bg),
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            PinKeypad(
                onDigit = { d -> if (pin.length < 4) { pin += d; error = false } },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )
        }
    }
}

// ── Parental pin: set-up (enter + confirm) ───────────────────────────────────

@Composable
private fun ParentalPinSetup(
    onCancel: () -> Unit,
    viewModel: SettingsViewModel,
) {
    var stage by remember { mutableStateOf(0) } // 0 = enter, 1 = confirm
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.length == 4) {
            when (stage) {
                0 -> { firstPin = pin; pin = ""; stage = 1 }
                1 -> {
                    if (pin == firstPin) {
                        viewModel.setParentalPin(pin)
                        onCancel()
                    } else {
                        mismatch = true
                        pin = ""
                        stage = 0
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { onCancel() },
            )
            Spacer(Modifier.width(8.dp))
            Text("set parental pin", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (stage == 0) "choose a 4-digit pin" else "confirm the pin",
                style = BonyType.meta.copy(
                    color = if (mismatch && stage == 0) BonyColors.Danger else BonyColors.TextMute,
                ),
            )
            if (mismatch && stage == 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "pins didn't match — start over",
                    style = BonyType.meta.copy(color = BonyColors.Danger),
                )
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .border(1.dp, if (filled) BonyColors.Accent else BonyColors.Rule)
                            .background(if (filled) BonyColors.Accent else BonyColors.Bg),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            PinKeypad(
                onDigit = { d -> if (pin.length < 4) { pin += d; mismatch = false } },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )
        }
    }
}

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.Surface)
            .padding(12.dp),
    ) {
        Text(
            text = title,
            style = BonyType.caption.copy(color = BonyColors.Accent),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun FilterChips(words: List<String>, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        words.forEach { word ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BonyColors.Rule)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = word,
                    style = BonyType.meta.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "✕",
                    style = BonyType.body.copy(color = BonyColors.Danger),
                    modifier = Modifier.clickable { onRemove(word) }.padding(4.dp),
                )
            }
        }
    }
}
