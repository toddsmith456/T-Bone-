package social.tbone.ui.decoy

import social.tbone.ui.theme.appBorder
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.notes.DecoyNotesViewModel
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

/**
 * Decoy vault info — identical to [social.tbone.ui.notes.NotesInfoScreen] but
 * with a hidden 10× tap on the "key storage" card that unlocks the full app.
 * The tap counter resets if more than 2 seconds pass between taps.
 */
@Composable
fun DecoyNotesInfoScreen(
    onBack: () -> Unit,
    onUnlock: () -> Unit,
    viewModel: DecoyNotesViewModel = hiltViewModel(),
) {
    val holder: DecoyInfoHolder = hiltViewModel()
    val appSettings = holder.appSettings
    val encryptionVerified by viewModel.encryptionVerified.collectAsStateWithLifecycle()
    val hardwareBacked by viewModel.hardwareBacked.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }

    fun handleKeyStorageTap() {
        val now = System.currentTimeMillis()
        if (now - lastTap > 2000) tapCount = 0
        lastTap = now
        tapCount++
        if (tapCount >= 10) {
            tapCount = 0
            appSettings.setDecoyUnlocked(true)
            Toast.makeText(context, "Full app unlocked", Toast.LENGTH_SHORT).show()
            onUnlock()
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
            Text(
                text = "encryption",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            InfoCard(title = "how it works") {
                Text(
                    text = "Notes and checklists are encrypted on this device " +
                        "before anything is written to storage. The database only " +
                        "holds ciphertext — the raw text never touches disk and " +
                        "nothing is ever uploaded.",
                    style = BonyType.meta.copy(color = BonyColors.TextDim),
                )
            }

            InfoCard(title = "cipher") {
                StatRow("algorithm", viewModel.cipherLabel)
                StatRow("key size", "256-bit AES")
                StatRow("authentication", "${viewModel.tagBits}-bit GCM tag")
                StatRow("random IV", "${viewModel.ivLength} bytes, fresh per save")
                StatRow("integrity", "tampering detected & rejected")
            }

            // Secret tap target — visually identical, but clickable.
            Box(modifier = Modifier.clickable { handleKeyStorageTap() }) {
                InfoCard(title = "key storage") {
                    StatRow("where", "Android Keystore")
                    StatRow("hardware-backed", if (hardwareBacked) "yes (secure chip)" else "no (software keystore)")
                    StatRow("key leaves device", "never")
                    StatRow("plaintext stored", "never")
                }
            }

            InfoCard(title = "self-test") {
                StatRow(
                    "encrypt → decrypt round-trip",
                    if (encryptionVerified) "verified" else "failed",
                    ok = encryptionVerified,
                )
                Text(
                    text = "Runs at startup. The vault only reports itself " +
                        "encrypted when this passes.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            InfoCard(title = "your vault") {
                StatRow("notes", "${stats.noteCount}")
                StatRow("checklists", "${stats.checklistCount}")
                StatRow("ciphertext stored", formatBytes(stats.totalCiphertextBytes))
            }

            Text(
                text = "all encryption happens on this device · nothing is uploaded",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}

@dagger.hilt.android.lifecycle.HiltViewModel
class DecoyInfoHolder @javax.inject.Inject constructor(
    val appSettings: social.tbone.settings.AppSettings,
) : androidx.lifecycle.ViewModel()

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .appBorder(1.dp, BonyColors.Rule)
            .background(BonyColors.Surface)
            .padding(14.dp),
    ) {
        Text(
            text = title,
            style = BonyType.caption.copy(color = BonyColors.Accent),
            modifier = Modifier.padding(bottom = 10.dp),
        )
        content()
    }
}

@Composable
private fun StatRow(label: String, value: String, ok: Boolean? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = BonyType.meta.copy(
                color = when (ok) {
                    true -> BonyColors.Accent
                    false -> BonyColors.Danger
                    null -> BonyColors.Text
                },
            ),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
