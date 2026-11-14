package social.tbone.ui.media

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import social.tbone.ai.ScanDecision
import social.tbone.ai.ScanVerdict
import social.tbone.settings.LocalContentFilter
import social.tbone.settings.LocalParentalPinVerifier
import social.tbone.ui.lock.PinKeypadLayout
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape

/**
 * Veil rendered in place of an image blocked by [social.tbone.ai.NsfwCoilInterceptor].
 *
 * - In [ScanDecision.HIDE] mode: shows a minimal muted strip with no reveal action.
 * - In [ScanDecision.COVER] mode: shows an explicit/sensitive warning with tap-to-reveal
 *   (or a 4-digit Parental PIN gate when Parental PIN protection is active).
 */
@Composable
fun AiNsfwMediaVeil(
    verdict: ScanVerdict,
    modifier: Modifier = Modifier,
    onReveal: () -> Unit,
) {
    val filter = LocalContentFilter.current
    val requirePin = filter.aiFilterConfig.effectiveRequirePinToReveal
    var showPinDialog by remember { mutableStateOf(false) }

    val shape = appCornerShape(MaterialTheme.shapes.medium)
    if (verdict.decision == ScanDecision.HIDE) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BonyColors.SurfaceAlt, shape)
                .border(1.dp, BonyColors.Rule, shape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "[ media hidden by offline ai filter ]",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BonyColors.SurfaceAlt)
            .clickable {
                if (requirePin) showPinDialog = true else onReveal()
            }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (verdict.isExplicit) "explicit media hidden" else "sensitive media hidden",
                style = BonyType.body.copy(
                    color = BonyColors.Text,
                    fontWeight = FontWeight.SemiBold,
                ),
                textAlign = TextAlign.Center,
            )
            Text(
                text = buildString {
                    append("offline ai filter")
                    if (verdict.scores.stageReached >= 2) append(" · marqo verified")
                    if (requirePin) append(" · pin locked")
                },
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            val btnShape = appCornerShape(MaterialTheme.shapes.small)
            Text(
                text = if (requirePin) "[ enter pin to reveal ]" else "[ tap to reveal ]",
                style = BonyType.meta.copy(
                    color = BonyColors.Accent,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier
                    .clip(btnShape)
                    .background(BonyColors.Bg, btnShape)
                    .border(1.dp, BonyColors.Rule, btnShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    if (showPinDialog) {
        ParentalPinRevealDialog(
            onDismiss = { showPinDialog = false },
            onUnlocked = {
                showPinDialog = false
                onReveal()
            },
        )
    }
}

@Composable
private fun ParentalPinRevealDialog(
    onDismiss: () -> Unit,
    onUnlocked: () -> Unit,
) {
    val verifyPin = LocalParentalPinVerifier.current
    val scope = rememberCoroutineScope()
    var entered by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    val rows = remember { PinKeypadLayout.rows(PinKeypadLayout.DEFAULT_DIGITS) }
    val shape = appCornerShape(MaterialTheme.shapes.medium)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(BonyColors.Bg, shape)
                .border(1.dp, BonyColors.Rule, shape)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "PARENTAL PIN",
                style = BonyType.tag.copy(color = BonyColors.Text),
            )
            Text(
                text = "enter 4-digit parental pin to reveal this image",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                textAlign = TextAlign.Center,
            )

            // 4 PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { idx ->
                    val filled = idx < entered.length
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (filled) BonyColors.Accent else BonyColors.SurfaceAlt)
                            .border(1.dp, if (filled) BonyColors.Accent else BonyColors.Rule, CircleShape),
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText!!,
                    style = BonyType.meta.copy(color = BonyColors.Danger),
                )
            }

            // Compact 4x3 keypad using T-Bone's PinKeypadLayout
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { key ->
                            val keyShape = appCornerShape(MaterialTheme.shapes.small)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(keyShape)
                                    .background(
                                        if (key.isEmpty()) BonyColors.Bg else BonyColors.SurfaceAlt,
                                        keyShape,
                                    )
                                    .then(
                                        if (key.isEmpty()) {
                                            Modifier
                                        } else {
                                            Modifier
                                                .border(1.dp, BonyColors.Rule, keyShape)
                                                .clickable {
                                                    errorText = null
                                                    when (key) {
                                                        "←" -> if (entered.isNotEmpty()) entered = entered.dropLast(1)
                                                        else -> if (entered.length < 4) {
                                                            val next = entered + key
                                                            entered = next
                                                            if (next.length == 4) {
                                                                scope.launch {
                                                                    if (verifyPin(next)) {
                                                                        onUnlocked()
                                                                    } else {
                                                                        entered = ""
                                                                        errorText = "incorrect pin"
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key.isNotEmpty()) {
                                    Text(
                                        text = key,
                                        style = BonyType.body.copy(
                                            color = BonyColors.Text,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "[ cancel ]",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}
