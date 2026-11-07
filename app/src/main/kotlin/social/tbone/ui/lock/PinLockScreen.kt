package social.tbone.ui.lock

import social.tbone.ui.theme.appBorder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val scope = rememberCoroutineScope()
    val scrambleKeypad by viewModel.keypadScrambled.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var keypadAttempt by remember { mutableIntStateOf(0) }
    val keypadDigits = remember(scrambleKeypad, keypadAttempt) {
        PinKeypadLayout.digits(scramble = scrambleKeypad)
    }
    var isError by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var retryMessage by remember { mutableStateOf("") }
    val shakeOffset = remember { Animatable(0f) }

    fun submitPin() {
        if (isSubmitting || pin.length != 4) return
        val attemptedPin = pin
        isSubmitting = true
        scope.launch {
          try {
            // Duress pin: wipe all app data instead of unlocking. This happens
            // silently (the app restarts into onboarding) so the duress pin is
            // indistinguishable from a wrong pin at the moment of entry.
            if (viewModel.handleDuressPin(attemptedPin)) {
                return@launch
            }
            val ok = viewModel.verifyPin(attemptedPin)
            if (ok) {
                viewModel.unlock()
                onUnlocked()
            } else {
                val wait = viewModel.retryAfterMillis()
                retryMessage = if (wait > 0) "try again in ${(wait + 999) / 1000} seconds" else ""
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
                keypadAttempt++
                isError = false
            }
          } catch (cancelled: kotlinx.coroutines.CancellationException) {
              throw cancelled
          } catch (error: Exception) {
              timber.log.Timber.e(error, "PIN verification failed")
              retryMessage = "couldn't verify pin; try again"
              pin = ""
              isError = false
          } finally {
              isSubmitting = false
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

            if (retryMessage.isNotEmpty()) Text(retryMessage, style = BonyType.caption.copy(color = BonyColors.Danger))
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
                            .appBorder(1.dp, if (filled) color else BonyColors.Rule)
                            .background(if (filled) color else BonyColors.Bg),
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            // Numpad
            PinKeypad(
                digits = keypadDigits,
                enabled = !isSubmitting,
                onDigit = { d -> if (!isSubmitting && pin.length < 4) pin += d },
                onBackspace = { if (!isSubmitting && pin.isNotEmpty()) pin = pin.dropLast(1) },
            )

        }
    }
}

@Composable
internal fun PinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    digits: List<String> = PinKeypadLayout.DEFAULT_DIGITS,
    enabled: Boolean = true,
) {
    val rows = PinKeypadLayout.rows(digits)
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
                                        .appBorder(1.dp, BonyColors.Rule)
                                        .clickable(enabled = enabled) { onBackspace() }
                                    else -> Modifier
                                        .appBorder(1.dp, BonyColors.Rule)
                                        .clickable(enabled = enabled) { onDigit(key) }
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
