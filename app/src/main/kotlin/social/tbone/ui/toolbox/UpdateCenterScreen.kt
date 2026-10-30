package social.tbone.ui.toolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.BuildConfig
import social.tbone.ui.onboarding.BonyButton
import social.tbone.ui.settings.SettingsViewModel
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.updates.SaveToDownloadsResult
import social.tbone.updates.UpdateInstallResult
import social.tbone.updates.UpdateState

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
            if (state.updateAvailable) {
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).background(BonyColors.Accent, CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("new", style = BonyType.tag.copy(color = BonyColors.Accent))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        if (BuildConfig.IS_BETA) {
            Text(
                "Beta builds do not check for or install main releases. Download a new beta APK from GitHub Actions.",
                modifier = Modifier.padding(14.dp),
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        } else {
            UpdateCenterContent(
                state = state,
                viewModel = viewModel,
                context = context,
            )
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun UpdateCenterContent(
    state: UpdateState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    var changelogExpanded by rememberSaveable { mutableStateOf(false) }
    var certExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Installed header ──
        Column(
            modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "installed v${BuildConfig.VERSION_NAME} · ${if (state.updateAvailable) "update available" else "up to date"}",
                style = BonyType.body.copy(color = if (state.updateAvailable) BonyColors.Accent else BonyColors.Text),
            )
            Text(
                text = "checks GitHub daily · ${context.packageName} · vc ${BuildConfig.VERSION_CODE}",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            if (state.lastCheckedAt > 0L) {
                Text(
                    text = "last check · ${android.text.format.DateFormat.getDateFormat(context).format(java.util.Date(state.lastCheckedAt))} ${android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(state.lastCheckedAt))}",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            if (state.latestVersion != null) {
                Text(
                    text = if (state.updateAvailable) "latest on GitHub · v${state.latestVersion}" else "latest · v${state.latestVersion}",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            // status line
            val status = when {
                state.isChecking && state.latestVersion == null -> "checking for releases…"
                state.updateAvailable -> "update available · v${state.latestVersion}"
                state.latestVersion != null -> "up to date · latest is v${state.latestVersion}"
                state.isChecking -> "checking GitHub…"
                else -> "release status not checked yet"
            }
            Text(
                text = status,
                style = BonyType.meta.copy(color = if (state.updateAvailable) BonyColors.Accent else BonyColors.TextDim),
            )
        }

        // ── Error banner ──
        state.error?.let { error ->
            Column(
                modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.WarnDim).background(BonyColors.WarnBg).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("⚠ ${error}", style = BonyType.meta.copy(color = BonyColors.Warn))
                if (error.contains("debug", ignoreCase = true) || error.contains("different app", ignoreCase = true)) {
                    Text(
                        "Debug builds can't install release APKs in-app due to package name (social.tbone.fork.debug vs social.tbone.fork). Use “Save to Downloads” then install from your file manager.",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
                if (error.contains("signing certificate", ignoreCase = true)) {
                    Text(
                        "Certificate mismatch usually means the installed build was signed with a different key (debug key vs release key). Save to Downloads lets you verify the hash manually.",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
            }
        }

        // ── Download progress ──
        if (state.isDownloading) {
            Column(
                modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.AccentDim).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = state.downloadProgress?.let { "downloading · $it%${state.apkSizeBytes?.let { size -> " · ${size / 1024 / 1024} MB" } ?: ""}" } ?: "downloading update…",
                    style = BonyType.meta.copy(color = BonyColors.Accent),
                )
                if (state.downloadProgress != null) {
                    LinearProgressIndicator(
                        progress = state.downloadProgress / 100f,
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = BonyColors.Accent,
                        trackColor = BonyColors.Rule,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = BonyColors.Accent,
                        trackColor = BonyColors.Rule,
                    )
                }
            }
        }

        // ── Primary actions ──
        Column(
            modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("actions", style = BonyType.caption.copy(color = BonyColors.TextMute))
            // Row 1: download / install
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val signingMismatch = state.error?.contains("signing certificate", ignoreCase = true) == true
                // Show download unless already ready — even with signing mismatch we allow retry;
                // the error banner explains why install is blocked.
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
                } else if (!state.apkUrl.isNullOrBlank()) {
                    BonyButton(
                        label = if (state.isDownloading) "downloading…" else "download update",
                        primary = true,
                        enabled = !state.isDownloading,
                        modifier = Modifier.weight(1f),
                        onClick = viewModel::downloadUpdate,
                    )
                }
                // Always allow saving to Downloads if ready, and also as fallback after error
                if (state.apkReady) {
                    BonyButton(
                        label = "save to Downloads",
                        primary = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            when (viewModel.saveToDownloads(context)) {
                                SaveToDownloadsResult.SAVED -> Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_LONG).show()
                                SaveToDownloadsResult.NOT_READY -> Toast.makeText(context, "No APK cached yet — download first", Toast.LENGTH_SHORT).show()
                                SaveToDownloadsResult.FAILED -> Toast.makeText(context, "Save failed — see error above", Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                } else if (!state.apkUrl.isNullOrBlank()) {
                    // Offer direct GitHub download as fallback
                    BonyButton(
                        label = "GitHub release",
                        primary = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))) }
                        },
                    )
                }
            }
            // Row 2: GitHub + check now
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.apkReady) {
                    BonyButton(
                        label = "GitHub release",
                        primary = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))) }
                        },
                    )
                }
                BonyButton(
                    label = if (state.isChecking) "checking…" else "check now",
                    primary = false,
                    enabled = !state.isChecking,
                    modifier = Modifier.weight(1f),
                    onClick = viewModel::checkForUpdates,
                )
            }
            if (state.apkReady) {
                Text(
                    text = "APK cached and verified. Tap install to open Android's package installer, or Save to Downloads for manual install / offline backup.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            } else if (state.updateAvailable) {
                Text(
                    text = "Downloads the release APK that matches this install's package name and signing certificate, then verifies SHA-256 from SHA256SUMS.txt.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
        }

        // ── Collapsible: Full changelog ──
        if (!state.releaseNotes.isNullOrBlank() || state.updateAvailable) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .appBorder(1.dp, BonyColors.Rule)
                    .clickable { changelogExpanded = !changelogExpanded }
                    .padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("full changelog", style = BonyType.body.copy(color = BonyColors.Text))
                        Text(if (changelogExpanded) "⌃" else "⌄", style = BonyType.body.copy(color = BonyColors.Accent))
                    }
                    Text(
                        text = if (state.releaseNotes.isNullOrBlank()) "No changelog published — open GitHub release for details" else if (changelogExpanded) "tap to collapse" else "tap to expand · ${state.releaseNotes.lines().size} lines",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                    AnimatedVisibility(visible = changelogExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BonyColors.SurfaceAlt, RoundedCornerShape(6.dp))
                                .border(1.dp, BonyColors.Rule, RoundedCornerShape(6.dp))
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = state.releaseNotes ?: "No notes",
                                style = BonyType.meta.copy(color = BonyColors.TextDim),
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .appBorder(1.dp, BonyColors.Rule)
                                    .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.releaseUrl))) } }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("open on GitHub", style = BonyType.tag.copy(color = BonyColors.Accent))
                            }
                        }
                    }
                }
            }
        } else if (state.latestVersion == null) {
            // No release yet — still show collapsed placeholder
            Box(
                modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("full changelog", style = BonyType.body.copy(color = BonyColors.Text))
                    Text("Check for updates to load the latest release notes", style = BonyType.caption.copy(color = BonyColors.TextMute))
                }
            }
        }

        // ── Collapsible: Certificate & hash ──
        Box(
            modifier = Modifier.fillMaxWidth()
                .appBorder(1.dp, BonyColors.Rule)
                .clickable { certExpanded = !certExpanded }
                .padding(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("certificate & hash", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(if (certExpanded) "⌃" else "⌄", style = BonyType.body.copy(color = BonyColors.Accent))
                }
                Text(
                    text = if (certExpanded) "tap to collapse — verify this is the right APK" else "tap to expand · SHA-256 & signing cert",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
                AnimatedVisibility(visible = certExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BonyColors.SurfaceAlt, RoundedCornerShape(6.dp))
                            .border(1.dp, BonyColors.Rule, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Installed certs
                        CertificateRow(
                            label = "installed cert (SHA-256)",
                            hashes = viewModel.installedCertHashes(),
                            context = context,
                            note = "Matches any APK signed with this repo owner's release key. If you sideloaded a debug build, this will differ from the release.",
                        )
                        // Expected APK SHA (from SHA256SUMS.txt)
                        if (!state.apkSha256.isNullOrBlank()) {
                            HashRow(label = "expected APK SHA-256 (from SHA256SUMS.txt)", hash = state.apkSha256, context = context)
                            state.sha256sUrl?.let { url ->
                                Text(
                                    text = "source: $url",
                                    style = BonyType.metaDim.copy(color = BonyColors.TextMute, fontFamily = FontFamily.Monospace),
                                )
                            }
                        } else if (state.apkUrl != null) {
                            Text(
                                "Expected SHA-256 not yet fetched — check again or open GitHub release for SHA256SUMS.txt",
                                style = BonyType.caption.copy(color = BonyColors.Warn),
                            )
                        }
                        // Downloaded file SHA if ready
                        if (state.apkReady) {
                            val fileSha = viewModel.downloadedFileSha256()
                            if (!fileSha.isNullOrBlank()) {
                                HashRow(label = "downloaded file SHA-256", hash = fileSha, context = context)
                                val match = fileSha.equals(state.apkSha256, ignoreCase = true)
                                Text(
                                    text = if (!state.apkSha256.isNullOrBlank()) {
                                        if (match) "✓ file hash matches expected — safe to install" else "✗ file hash DOES NOT match expected — do not install"
                                    } else "File hash computed locally — compare with GitHub SHA256SUMS.txt",
                                    style = BonyType.caption.copy(color = if (!state.apkSha256.isNullOrBlank() && match) BonyColors.Accent else BonyColors.Warn),
                                )
                            }
                            // Downloaded certs
                            val dlCerts = viewModel.downloadedCertHashes()
                            if (dlCerts.isNotEmpty()) {
                                CertificateRow(
                                    label = "downloaded APK certs (SHA-256)",
                                    hashes = dlCerts,
                                    context = context,
                                    note = "Should match installed cert above for in-app install. Mismatch = different signing key.",
                                )
                                val installed = viewModel.installedCertHashes().toSet()
                                val downloaded = dlCerts.toSet()
                                val certMatch = installed.intersect(downloaded).isNotEmpty() || installed == downloaded
                                Text(
                                    text = if (certMatch) "✓ certificate matches installed — in-app install allowed" else "✗ certificate mismatch — save to Downloads for manual check",
                                    style = BonyType.caption.copy(color = if (certMatch) BonyColors.Accent else BonyColors.Warn),
                                )
                            }
                        }
                        // APK size
                        state.apkSizeBytes?.let { size ->
                            Text("APK size: ${size / 1024 / 1024} MB (${size} bytes)", style = BonyType.caption.copy(color = BonyColors.TextMute))
                        }
                        // Help row
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .appBorder(1.dp, BonyColors.Rule)
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("how to verify manually", style = BonyType.caption.copy(color = BonyColors.TextMute))
                                Text(
                                    "1) Download SHA256SUMS.txt from GitHub release.\n2) Run: sha256sum -c SHA256SUMS.txt (or compare hash above).\n3) For cert: keytool -printcert -jarfile T-Bone-*.apk | grep SHA256",
                                    style = BonyType.metaDim.copy(color = BonyColors.TextMute, fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Debug / links ──
        Column(
            modifier = Modifier.fillMaxWidth().appBorder(1.dp, BonyColors.Rule).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("troubleshooting", style = BonyType.caption.copy(color = BonyColors.TextMute))
            if (state.apkUrl != null) {
                ClickableMonoRow(label = "APK URL", value = state.apkUrl, context = context)
            }
            ClickableMonoRow(label = "release page", value = state.releaseUrl, context = context)
            Text(
                "If download stalls, check Tor toggle (Settings → Privacy). Direct retry is automatic after Tor failure.",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun CertificateRow(label: String, hashes: List<String>, context: Context, note: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = BonyType.caption.copy(color = BonyColors.TextMute))
        if (hashes.isEmpty()) {
            Text("unavailable — app not installed or cert not readable", style = BonyType.meta.copy(color = BonyColors.Warn))
        } else {
            hashes.forEach { h ->
                ClickableMonoRow(label = "", value = h, context = context)
            }
        }
        note?.let { Text(it, style = BonyType.caption.copy(color = BonyColors.TextMute)) }
    }
}

@Composable
private fun HashRow(label: String, hash: String, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = BonyType.caption.copy(color = BonyColors.TextMute))
        ClickableMonoRow(label = "", value = hash.lowercase(), context = context)
    }
}

@Composable
private fun ClickableMonoRow(label: String, value: String, context: Context) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label.ifBlank { "hash" }, value))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .background(BonyColors.Surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (label.isNotBlank()) Text(label, style = BonyType.caption.copy(color = BonyColors.TextMute))
            Text(value, style = BonyType.meta.copy(color = BonyColors.Text, fontFamily = FontFamily.Monospace))
        }
        Text("copy", style = BonyType.tag.copy(color = BonyColors.Accent))
    }
}

