package social.tbone.ui.toolbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.BuildConfig
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.settings.SettingsViewModel
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.updates.UpdateInstallResult

/** Release checking, download and verified installation, moved out of Settings into Toolbox. */
@Composable
fun UpdateCenterScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.updateState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(8.dp))
            Text("update center", style = BonyType.body.copy(color = BonyColors.Text))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        UpdateCenterContent(state = state, viewModel = viewModel, context = context)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun UpdateCenterContent(
    state: social.tbone.updates.UpdateState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "installed v${BuildConfig.VERSION_NAME} · checks GitHub daily",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(5.dp))
        val status = when {
            state.isChecking && state.latestVersion == null -> "checking for releases…"
            state.updateAvailable -> "update available · v${state.latestVersion}"
            state.latestVersion != null -> "up to date · latest is v${state.latestVersion}"
            state.isChecking -> "checking GitHub…"
            else -> "release status not checked yet"
        }
        Text(
            text = status,
            style = BonyType.body.copy(
                color = if (state.updateAvailable) BonyColors.Accent else BonyColors.Text,
            ),
        )
        if (state.lastCheckedAt > 0L) {
            Text(
                text = "last check · ${android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(state.lastCheckedAt))}",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
        state.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Text(error, style = BonyType.meta.copy(color = BonyColors.Warn))
        }
        if (state.updateAvailable && !state.releaseNotes.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = state.releaseNotes.take(1_500),
                style = BonyType.meta.copy(color = BonyColors.TextDim),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.isDownloading) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = state.downloadProgress?.let { "downloading · $it%" } ?: "downloading update…",
                style = BonyType.meta.copy(color = BonyColors.Accent),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.updateAvailable) {
                val signingMismatch = state.error?.contains("signing certificate", ignoreCase = true) == true
                if (state.apkReady) {
                    BonyButton(
                        label = "install update",
                        primary = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (viewModel.installDownloadedUpdate(context)) {
                                UpdateInstallResult.NEEDS_PERMISSION -> runCatching {
                                    context.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                                UpdateInstallResult.FAILED,
                                UpdateInstallResult.NOT_READY,
                                UpdateInstallResult.STARTED -> Unit
                            }
                        },
                    )
                } else if (!state.apkUrl.isNullOrBlank() && !signingMismatch) {
                    BonyButton(
                        label = if (state.isDownloading) "downloading…" else "download update",
                        primary = true,
                        enabled = !state.isDownloading,
                        modifier = Modifier.weight(1f),
                        onClick = viewModel::downloadUpdate,
                    )
                }
                BonyButton(
                    label = "GitHub release",
                    primary = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl)))
                        }
                    },
                )
            }
            BonyButton(
                label = if (state.isChecking) "checking…" else "check now",
                primary = false,
                enabled = !state.isChecking,
                modifier = if (state.updateAvailable) Modifier.weight(1f) else Modifier,
                onClick = viewModel::checkForUpdates,
            )
        }
        if (state.updateAvailable && state.apkReady) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "APK package and signing certificate verified for this installation.",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}
