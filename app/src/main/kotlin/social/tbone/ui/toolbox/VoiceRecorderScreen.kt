package social.tbone.ui.toolbox

import social.tbone.ui.theme.appBorder
import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.tbone.media.MimeTypes
import social.tbone.ui.components.LockBadge
import social.tbone.ui.permissions.rememberPermissionRequester
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.io.File
import kotlin.math.roundToInt

/**
 * Voice recorder tool: asks for microphone permission, records with Low /
 * Medium / High quality, supports Pause/Resume and Stop, then a mini player
 * lets you listen before the actions appear (save, upload to the Write
 * composer for a kind-1 note, or share the audio outside the app).
 */
@Composable
fun VoiceRecorderScreen(
    onBack: () -> Unit,
    onUploadToWrite: (String) -> Unit,
    viewModel: VoiceRecorderViewModel = hiltViewModel(),
) {

    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    val recordingsDir = remember(context) {
        File(context.filesDir, "recordings")
    }

    // Microphone permission — asked on the first record tap, with proper
    // handling for permanent denial (open settings) like every other tool.
    val micPerm = rememberPermissionRequester()

    fun onRecordTap() {
        micPerm.requestOrRun(
            permissions = listOf(Manifest.permission.RECORD_AUDIO),
            action = { viewModel.startRecording(recordingsDir) },
        )
    }

    // Save to device: system "create document" picker — any location on device.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/*")
    ) { uri: Uri? ->
        val file = state.activeFile ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().use { input -> input.copyTo(output) }
                        } != null
                    }.getOrDefault(false)
                }
                status = if (ok) "saved" else "save failed"
            }
        }
    }

    fun shareOutside() {
        val file = state.activeFile ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val mime = MimeTypes.mimeForExtension(file.extension) ?: "audio/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share voice note"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
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
                text = "voice recorder",
                style = BonyType.body.copy(color = BonyColors.Text),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(20.dp))

            // ── Quality selector (disabled while recording) ───────────────────
            Text("quality", style = BonyType.caption.copy(color = BonyColors.TextMute))
            Spacer(Modifier.height(8.dp))
            Row {
                VoiceQuality.entries.forEach { q ->
                    val active = q == state.quality
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .appBorder(
                                1.dp,
                                if (active) BonyColors.Accent else BonyColors.Rule,
                            )
                            .then(
                                if (!state.isRecording) Modifier.clickable { viewModel.setQuality(q) }
                                else Modifier,
                            )
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = q.label,
                            style = BonyType.tag.copy(
                                color = if (active) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Timer ─────────────────────────────────────────────────────────
            Text(
                text = formatElapsed(state.elapsedMs),
                style = BonyType.title.copy(color = BonyColors.Text),
            )

            Spacer(Modifier.height(18.dp))

            // ── Record / Pause / Stop controls ────────────────────────────────
            if (!state.isRecording) {
                // Idle: big record button.
                RecordControl(
                    label = "record",
                    isRecording = false,
                    size = 84.dp,
                    enabled = !state.isAnonymizing,
                    onClick = { onRecordTap() },
                )
            } else {
                // Recording (or paused): pause/resume + stop.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordControl(
                        label = if (state.isPaused) "resume" else "pause",
                        isRecording = true,
                        size = 56.dp,
                        onClick = {
                            if (state.isPaused) viewModel.resumeRecording()
                            else viewModel.pauseRecording()
                        },
                    )
                    Spacer(Modifier.width(24.dp))
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .border(2.dp, BonyColors.Danger, CircleShape)
                            .clickable { viewModel.stopRecording() }
                            .background(BonyColors.Danger.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(BonyColors.Danger, RoundedCornerShape(4.dp)),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.isPaused) "paused — tap resume" else "tap stop when done",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }

            // ── Mini player + share/save — only after a finished recording ────
            if (state.hasFinishedRecording) {
                Spacer(Modifier.height(20.dp))
                MiniPlayer(
                    isPlaying = state.isPreviewPlaying,
                    positionMs = state.previewPositionMs,
                    durationMs = state.previewDurationMs,
                    onTogglePlay = viewModel::togglePreview,
                    onSeek = viewModel::seekPreview,
                )

                Spacer(Modifier.height(14.dp))

                if (state.isAnonymous) {
                    LockBadge(
                        label = "modified output · each re-render starts from the original recording",
                        tint = BonyColors.Accent,
                        iconSize = 14.dp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                DistortionSliders(
                    speed = state.speed,
                    pitch = state.pitch,
                    drive = state.drive,
                    enabled = !state.isAnonymizing,
                    onSpeed = viewModel::setSpeed,
                    onPitch = viewModel::setPitch,
                    onDrive = viewModel::setDrive,
                )
                Spacer(Modifier.height(6.dp))
                ActionButton(
                    label = when {
                        state.isAnonymizing -> "◌ MODIFYING VOICE…"
                        state.isAnonymous -> "◉ RE-MODIFY VOICE"
                        else -> "◉ MODIFY VOICE"
                    },
                    enabled = !state.isAnonymizing,
                ) { viewModel.anonymize() }

                Text(
                    text = "Voice effects do not guarantee anonymity. Your voice or what you say may still identify you.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(6.dp))
                ActionButton("↓ save to device", enabled = !state.isAnonymizing) {
                    val extension = state.activeFile?.extension?.takeIf { it.isNotBlank() } ?: "m4a"
                    saveLauncher.launch("voice_${System.currentTimeMillis()}.$extension")
                }
                ActionButton("↑ upload audio to kind 1", enabled = !state.isAnonymizing) {
                    state.activeFile?.let { file -> onUploadToWrite(file.absolutePath) }
                }
                Text(
                    text = "opens Write and uploads this recording to a Blossom server",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                ActionButton("↗ share outside of app", enabled = !state.isAnonymizing) { shareOutside() }
            }

            // Permission feedback (one-time denial vs blocked in settings).
            micPerm.lastDenial?.let {
                Spacer(Modifier.height(12.dp))
                Text(text = it, style = BonyType.meta.copy(color = BonyColors.Danger))
            }
            if (micPerm.permanentlyDenied.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "microphone blocked in system settings",
                        style = BonyType.meta.copy(color = BonyColors.Danger),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "open settings →",
                        style = BonyType.tag.copy(color = BonyColors.Accent),
                        modifier = Modifier
                            .appBorder(1.dp, BonyColors.Accent)
                            .clickable { micPerm.openSettings() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(text = it, style = BonyType.meta.copy(color = BonyColors.Accent))
            }
            state.error?.let {
                Spacer(Modifier.height(12.dp))
                Text(text = it, style = BonyType.meta.copy(color = BonyColors.Danger))
            }
            // Give the final action a deliberate buffer from system gesture/nav edges.
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The round record control (idle = circle, recording = square-ish). */
@Composable
private fun RecordControl(
    label: String,
    isRecording: Boolean,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .border(
                    width = 2.dp,
                    color = if (isRecording) BonyColors.Warn else BonyColors.Accent,
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick)
                .background(
                    if (isRecording) BonyColors.Warn.copy(alpha = 0.25f) else BonyColors.AccentBg,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(if (isRecording) 20.dp else 36.dp)
                    .background(
                        if (isRecording) BonyColors.Warn else BonyColors.Accent,
                        if (isRecording) RoundedCornerShape(4.dp) else CircleShape,
                    ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(text = label, style = BonyType.meta.copy(color = BonyColors.TextMute))
    }
}

/** Minimal inline player: play/pause, a progress bar, and time labels. */
@Composable
private fun MiniPlayer(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appBorder(1.dp, BonyColors.Rule)
            .background(BonyColors.Surface)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play / pause
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .border(1.dp, BonyColors.Accent, CircleShape)
                    .clickable(onClick = onTogglePlay)
                    .background(BonyColors.AccentBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPlaying) "❚❚" else "▶",
                    style = BonyType.body.copy(color = BonyColors.Accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            // Progress bar (tappable/draggable to seek)
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(22.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { pos ->
                            if (size.width > 0) onSeek(((pos.x / size.width) * durationMs).toLong())
                        }
                    }
                    .pointerInput(durationMs) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            if (size.width > 0) onSeek(((change.position.x / size.width) * durationMs).toLong())
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(BonyColors.Rule, RoundedCornerShape(2.dp))
                        .align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(BonyColors.Accent, RoundedCornerShape(2.dp))
                        .align(Alignment.Center),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${formatElapsed(positionMs)} / ${formatElapsed(durationMs)}",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "preview — listen before you save or share",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .appBorder(1.dp, if (enabled) BonyColors.Accent else BonyColors.Rule)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.button.copy(
                color = if (enabled) BonyColors.Accent else BonyColors.TextMute,
            ),
        )
    }
}

@Composable
private fun DistortionSliders(
    speed: Float,
    pitch: Float,
    drive: Float,
    enabled: Boolean,
    onSpeed: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onDrive: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appBorder(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        DistortionSlider(
            label = "speed",
            value = speed,
            valueRange = 0.5f..1.2f,
            enabled = enabled,
            display = { v -> if (v < 1f) "${((1f / v) * 100).toInt()}% slow" else "${((v) * 100).toInt()}% fast" },
            onValue = onSpeed,
        )
        Spacer(Modifier.height(8.dp))
        DistortionSlider(
            label = "pitch",
            value = pitch,
            valueRange = 0.8f..1.3f,
            enabled = enabled,
            display = { v -> if (v < 1f) "deeper" else if (v > 1f) "higher" else "normal" },
            onValue = onPitch,
        )
        Spacer(Modifier.height(8.dp))
        DistortionSlider(
            label = "distortion",
            value = drive,
            valueRange = 0f..8f,
            enabled = enabled,
            display = { v -> if (v < 0.5f) "none" else "${v.roundToInt()}" },
            onValue = onDrive,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "words stay understandable — identity is masked",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun DistortionSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    display: (Float) -> String,
    onValue: (Float) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = display(value),
                style = BonyType.tag.copy(color = BonyColors.Accent),
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            enabled = enabled,
            valueRange = valueRange,
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
