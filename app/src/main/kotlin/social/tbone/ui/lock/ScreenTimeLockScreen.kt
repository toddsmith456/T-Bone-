package social.tbone.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.launch
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Shown when the parental screen-time allowance is used up. The app is locked
 * until the parent enters the parental PIN; each correct PIN grants another
 * 30 minutes.
 */
@Composable
fun ScreenTimeLockScreen(
    onUnlocked: () -> Unit,
    verifyPin: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(pin) {
        if (pin.length == 4) {
            val ok = verifyPin(pin)
            if (ok) {
                onUnlocked()
            } else {
                error = true
                pin = ""
            }
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
                text = "⏳",
                style = BonyType.title.copy(color = BonyColors.Accent),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "screen time is up",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (error) "wrong pin — try again"
                else "enter the parental pin for 30 more minutes",
                style = BonyType.meta.copy(
                    color = if (error) BonyColors.Danger else BonyColors.TextMute,
                ),
            )

            Spacer(Modifier.height(32.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { i ->
                    val filled = i < pin.length
                    val color = if (error) BonyColors.Danger else BonyColors.Accent
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .border(1.dp, if (filled) color else BonyColors.Rule, RectangleShape)
                            .background(if (filled) color else BonyColors.Bg),
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            PinKeypad(
                onDigit = { d -> if (pin.length < 4) { pin += d; error = false } },
                onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
            )
        }
    }
}
