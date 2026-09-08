package social.tbone.ui.lock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import kotlin.math.roundToInt

@Composable
fun PinLockScreen(
    onUnlocked: () -> Unit,
    viewModel: PinLockViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }

    fun submitPin() {
        scope.launch {
            // Duress pin: wipe all app data instead of unlocking. This happens
            // silently (the app restarts into onboarding) so the duress pin is
            // indistinguishable from a wrong pin at the moment of entry.
            if (viewModel.isDuressPin(pin)) {
                viewModel.wipeData()
                return@launch
            }
            val ok = viewModel.verifyPin(pin)
            if (ok) {
                viewModel.unlock()
                onUnlocked()
            } else {
                isError = true
                shakeOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = keyframes {
                        durationMillis = 400
                        10f  at 50
                        -10f at 100
                        10f  at 150
                        -10f at 200
                        6f   at 250
                        -6f  at 300
                        0f   at 400
                    },
                )
                pin = ""
                isError = false
            }
        }
    }

    LaunchedEffect(pin) {
        if (pin.length == 4) submitPin()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "t-bone",
                style = BonyType.wordmark.copy(color = BonyColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "locked",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )

            Spacer(Modifier.height(48.dp))

            // PIN dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
            ) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    val color = if (isError) BonyColors.Danger else BonyColors.Accent
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .border(1.dp, if (filled) color else BonyColors.Rule, RectangleShape)
                            .background(if (filled) color else BonyColors.Bg),
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // Numpad
            PinKeypad(
                onDigit = { d -> if (pin.length < 4) pin += d },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )

        }
    }
}

@Composable
internal fun PinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("←", "0", ""),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { key ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .then(
                                when (key) {
                                    "" -> Modifier
                                    "←" -> Modifier
                                        .border(1.dp, BonyColors.Rule)
                                        .clickable { onBackspace() }
                                    else -> Modifier
                                        .border(1.dp, BonyColors.Rule)
                                        .clickable { onDigit(key) }
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key.isNotEmpty()) {
                            Text(
                                text = key,
                                style = BonyType.title.copy(color = BonyColors.Text),
                            )
                        }
                    }
                }
            }
        }
    }
}
