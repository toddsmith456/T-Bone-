package social.tbone.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

@Composable
fun PinSetupScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
    isDuress: Boolean = false,
    /** Optional override — when set, called with the confirmed PIN instead of the app-lock path. */
    onSave: ((String) -> Unit)? = null,
    viewModel: PinSetupViewModel = hiltViewModel(),
) {
    val scope = rememberCoroutineScope()
    var firstPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirmStep by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }

    val currentPin = if (isConfirmStep) confirmPin else firstPin
    val label = when {
        isError && isDuress -> "duress pin can't match your unlock pin — try again"
        isError -> "pins don't match — try again"
        isConfirmStep -> if (isDuress) "confirm duress pin" else "confirm PIN"
        else -> if (isDuress) "enter duress pin" else "enter new PIN"
    }
    val labelColor = if (isError) BonyColors.Danger else BonyColors.TextMute

    fun advance() {
        if (!isConfirmStep) {
            isConfirmStep = true
        } else {
            if (firstPin == confirmPin) {
                scope.launch {
                    if (onSave != null) {
                        onSave(firstPin)
                        onDone()
                    } else if (isDuress) {
                        // Guard: duress pin must differ from the unlock PIN,
                        // otherwise every unlock would wipe the app.
                        val ok = viewModel.setDuressPin(firstPin)
                        if (ok) {
                            onDone()
                        } else {
                            isError = true
                            firstPin = ""
                            confirmPin = ""
                            isConfirmStep = false
                        }
                    } else {
                        viewModel.setPin(firstPin)
                        onDone()
                    }
                }
            } else {
                isError = true
                firstPin = ""
                confirmPin = ""
                isConfirmStep = false
            }
        }
    }

    LaunchedEffect(firstPin, confirmPin) {
        if (!isConfirmStep && firstPin.length == 4) advance()
        else if (isConfirmStep && confirmPin.length == 4) advance()
    }

    LaunchedEffect(isError) {
        if (isError) {
            kotlinx.coroutines.delay(1500)
            isError = false
        }
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
                text = if (isDuress) "duress pin" else "app lock",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = BonyType.caption.copy(color = labelColor),
            )

            Spacer(Modifier.height(48.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < currentPin.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .border(
                                1.dp,
                                if (filled) BonyColors.Accent else BonyColors.Rule,
                                RectangleShape,
                            )
                            .background(
                                if (filled) BonyColors.Accent else BonyColors.Bg,
                            ),
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            PinKeypad(
                onDigit = { d ->
                    if (!isConfirmStep && firstPin.length < 4) firstPin += d
                    else if (isConfirmStep && confirmPin.length < 4) confirmPin += d
                },
                onBackspace = {
                    if (isConfirmStep && confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1)
                    else if (!isConfirmStep && firstPin.isNotEmpty()) firstPin = firstPin.dropLast(1)
                },
            )
        }
    }
}
