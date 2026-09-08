package social.tbone.ui.toolbox

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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import social.tbone.ui.lock.PinKeypad
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import kotlin.math.roundToInt

/**
 * Toolbox lock: a 4-digit numpad (same style as the app lock). Entering the
 * duress PIN wipes the toolbox instead of unlocking. Mirrors the app-lock
 * screen but is scoped to the Toolbox.
 */
@Composable
fun ToolboxLockScreen(
    onUnlocked: () -> Unit,
    viewModel: ToolboxLockViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val shakeOffset = remember { Animatable(0f) }

    fun submit() {
        scope.launch {
            if (viewModel.isDuressPin(pin)) {
                viewModel.wipeToolbox()
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
                        10f at 50; -10f at 100; 10f at 150; -10f at 200
                        6f at 250; -6f at 300; 0f at 400
                    },
                )
                pin = ""
                isError = false
            }
        }
    }

    LaunchedEffect(pin) { if (pin.length == 4) submit() }

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
                text = "toolbox",
                style = BonyType.wordmark.copy(color = BonyColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "locked",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )

            Spacer(Modifier.height(48.dp))

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

            PinKeypad(
                onDigit = { d -> if (pin.length < 4) pin += d },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )
        }
    }
}
