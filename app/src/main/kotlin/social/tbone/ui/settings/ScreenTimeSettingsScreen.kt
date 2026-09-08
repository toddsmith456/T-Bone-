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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Parental screen time: an exact daily allowance (hours + minutes). When the
 * allowance is used up the app locks itself and only the parental PIN unlocks
 * it (granting a 30-minute grace, after which it locks again).
 */
@Composable
fun ScreenTimeSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.screenTimeEnabled.collectAsStateWithLifecycle()
    val totalMinutes by viewModel.screenTimeMinutes.collectAsStateWithLifecycle()

    var hours by remember { mutableIntStateOf((totalMinutes / 60).coerceIn(0, 23)) }
    var minutes by remember { mutableIntStateOf((totalMinutes % 60).coerceIn(0, 59)) }

    fun apply() {
        viewModel.setScreenTimeMinutes(hours * 60 + minutes)
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
            Text("screen time", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // ── Enable toggle ─────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("screen time limit", style = BonyType.meta.copy(color = BonyColors.Text))
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "locks the app after the daily allowance and requires the parental pin to continue",
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp, 22.dp)
                        .background(if (enabled) BonyColors.AccentBg else BonyColors.Bg)
                        .border(1.dp, if (enabled) BonyColors.Accent else BonyColors.RuleStrong)
                        .clickable { viewModel.setScreenTimeEnabled(!enabled) }
                        .padding(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                            .background(if (enabled) BonyColors.Accent else BonyColors.TextMute),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            if (enabled) {
                Text(
                    text = "daily allowance",
                    style = BonyType.caption.copy(color = BonyColors.Accent),
                )
                Spacer(Modifier.height(10.dp))

                // ── Hours ─────────────────────────────────────────────────────
                Text("hours", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    StepperButton("−") { if (hours > 0) { hours--; apply() } }
                    Text(
                        text = "$hours",
                        style = BonyType.title.copy(color = BonyColors.Text),
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    StepperButton("+") { if (hours < 23) { hours++; apply() } }
                }

                Spacer(Modifier.height(18.dp))

                // ── Minutes ───────────────────────────────────────────────────
                Text("minutes", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
                ) {
                    StepperButton("−") { if (minutes > 0) { minutes--; apply() } }
                    Text(
                        text = "$minutes",
                        style = BonyType.title.copy(color = BonyColors.Text),
                        modifier = Modifier.width(48.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    StepperButton("+") { if (minutes < 59) { minutes++; apply() } }
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    text = "total: ${hours}h ${minutes}m per day",
                    style = BonyType.meta.copy(color = BonyColors.Accent),
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "when the allowance runs out the app locks. Entering the parental pin (set in content filters) grants another 30 minutes.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                )
            }
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(1.dp, BonyColors.Rule)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.body.copy(color = BonyColors.Accent),
        )
    }
}
