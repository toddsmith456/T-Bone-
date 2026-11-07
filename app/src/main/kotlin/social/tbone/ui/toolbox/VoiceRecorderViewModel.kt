package social.tbone.ui.toolbox

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.toolbox.VoiceAnonymizer
import java.io.File
import javax.inject.Inject

/** Recording quality presets for the voice recorder. */
enum class VoiceQuality(val label: String, val sampleRate: Int, val bitRate: Int) {
    LOW("LOW", 8000, 32_000),
    MEDIUM("MEDIUM", 22_050, 96_000),
    HIGH("HIGH", 44_100, 192_000),
}

/** UI state for the voice recorder. */
data class VoiceRecorderState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val quality: VoiceQuality = VoiceQuality.MEDIUM,
    val elapsedMs: Long = 0L,
    val currentFile: File? = null,
    /** Set after ANONYMIZE; the preview/save/share use this file instead. */
    val anonymousFile: File? = null,
    val isAnonymous: Boolean = false,
    val isAnonymizing: Boolean = false,
    // Three distortion sliders (defaults = midpoint config).
    val speed: Float = 0.78f,
    val pitch: Float = 1.0f,
    val drive: Float = 4.5f,
    val isPreviewPlaying: Boolean = false,
    val previewPositionMs: Long = 0L,
    val previewDurationMs: Long = 0L,
    val error: String? = null,
) {
    /** True once a full recording exists (after Stop) — share/save become available. */
    val hasFinishedRecording: Boolean
        get() = currentFile != null && !isRecording && !isPaused

    /** The file to preview/save/share: the anonymized one when present. */
    val activeFile: File?
        get() = anonymousFile ?: currentFile
}

@HiltViewModel
class VoiceRecorderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingGate: social.tbone.toolbox.VoiceRecordingGate,
    private val noteWrites: social.tbone.notes.NoteWriteCoordinator,
) : ViewModel() {

    private val anonymizer = VoiceAnonymizer()
    private val recordingOwner = Any()
    private val starting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _state = MutableStateFlow(VoiceRecorderState())
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var startedAt: Long = 0L
    private var ticker: Job? = null

    private var player: MediaPlayer? = null
    private var previewTicker: Job? = null

    fun setSpeed(value: Float) {
        if (_state.value.isRecording || _state.value.isAnonymizing) return
        _state.update { it.copy(speed = value) }
    }

    fun setPitch(value: Float) {
        if (_state.value.isRecording || _state.value.isAnonymizing) return
        _state.update { it.copy(pitch = value) }
    }

    fun setDrive(value: Float) {
        if (_state.value.isRecording || _state.value.isAnonymizing) return
        _state.update { it.copy(drive = value) }
    }

    fun setQuality(quality: VoiceQuality) {
        if (_state.value.isRecording) return // can't change mid-recording
        _state.update { it.copy(quality = quality) }
    }

    /** Begins a new recording into [dir]. Assumes microphone permission granted. */
    fun startRecording(dir: File) {
        if (_state.value.isRecording || _state.value.isAnonymizing || !starting.compareAndSet(false, true)) return
        val epoch = noteWrites.currentWipeEpoch()
        stopPreview()
        viewModelScope.launch {
            try {
                val q = _state.value.quality
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        recordingGate.start(recordingOwner, epoch) {
                            check(!disposed.get()) { "recorder closed" }
                            check(dir.isDirectory || dir.mkdirs()) { "recording directory unavailable" }
                            val file = File.createTempFile("voice_", ".m4a", dir)
                            val r = MediaRecorder(context)
                            try {
                                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                r.setAudioSamplingRate(q.sampleRate)
                                r.setAudioEncodingBitRate(q.bitRate)
                                r.setOutputFile(file.absolutePath)
                                r.prepare()
                                r.start()
                            } catch (failure: Exception) {
                                runCatching { r.release() }
                                file.delete()
                                throw failure
                            }
                            recorder = r
                            startedAt = System.currentTimeMillis()
                            _state.update { it.copy(isRecording = true, isPaused = false,
                                currentFile = file, anonymousFile = null, isAnonymous = false,
                                isAnonymizing = false, elapsedMs = 0L, error = null) }
                            startTicker()
                            val closeRecording: (Boolean) -> Unit = { discard ->
                                ticker?.cancel()
                                runCatching { r.stop() } // A too-short recording may not be finalizable.
                                r.release() // A release failure keeps the registration for a wipe retry.
                                recorder = null
                                if (discard) {
                                    _state.update { VoiceRecorderState(quality = it.quality) }
                                } else {
                                    _state.update { it.copy(isRecording = false, isPaused = false,
                                        anonymousFile = null, isAnonymous = false, isAnonymizing = false) }
                                }
                            }
                            closeRecording
                        }
                    }.getOrDefault(false)
                }
                if (!ok) _state.update { it.copy(error = "could not start recording") }
            } finally { starting.set(false) }
        }
    }

    fun pauseRecording() {
        if (!_state.value.isRecording || _state.value.isPaused) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { recordingGate.runIfOwned(recordingOwner) {
                    recorder?.pause()
                    ticker?.cancel()
                    _state.update { it.copy(isPaused = true) }
                } }.getOrDefault(false)
            }
            if (!ok && _state.value.isRecording) _state.update { it.copy(error = "could not pause recording") }
        }
    }

    fun resumeRecording() {
        if (!_state.value.isRecording || !_state.value.isPaused) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { recordingGate.runIfOwned(recordingOwner) {
                    recorder?.resume()
                    _state.update { it.copy(isPaused = false) }
                    startTicker()
                } }.getOrDefault(false)
            }
            if (!ok && _state.value.isRecording) _state.update { it.copy(error = "could not resume recording") }
        }
    }

    /** Stops recording and finalizes the file. */
    fun stopRecording() {
        if (!_state.value.isRecording) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { recordingGate.stop(recordingOwner) }.isSuccess
            }
            if (!ok) _state.update { it.copy(error = "could not stop recording") }
        }
    }

    /**
     * Renders an anonymous version from the original recording. Repeated calls
     * use that same source file with the latest slider values, never the prior
     * render, so each tweak is independent rather than cumulatively degraded.
     */
    fun anonymize() {
        val snapshot = _state.value
        val src = snapshot.currentFile ?: return
        if (snapshot.isRecording || snapshot.isPaused || snapshot.isAnonymizing) return
        val epoch = noteWrites.currentWipeEpoch()
        val speed = snapshot.speed
        val pitch = snapshot.pitch
        val drive = snapshot.drive
        _state.update { it.copy(isAnonymizing = true, error = null) }

        viewModelScope.launch {
            val anon = try {
                noteWrites.runCancellableAtEpoch(epoch) {
                    withContext(Dispatchers.IO) { anonymizer.anonymize(src, speed, pitch, drive) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (epoch != noteWrites.currentWipeEpoch()) {
                _state.update { VoiceRecorderState(quality = it.quality) }
                return@launch
            }
            if (anon != null) {
                stopPreview()
                _state.update { current ->
                    current.anonymousFile?.takeIf { it != anon }?.delete()
                    current.copy(
                        anonymousFile = anon,
                        isAnonymous = true,
                        isAnonymizing = false,
                        isPreviewPlaying = false,
                        previewPositionMs = 0L,
                        previewDurationMs = 0L,
                        error = null,
                    )
                }
            } else {
                _state.update {
                    it.copy(isAnonymizing = false, error = "anonymization failed")
                }
            }
        }
    }

    // ── Preview playback (listen before saving/sharing) ───────────────────────

    fun togglePreview() {
        val file = _state.value.activeFile ?: return
        val playing = _state.value.isPreviewPlaying
        if (playing) {
            pausePreview()
        } else {
            if (player == null) {
                playPreview(file)
            } else {
                resumePreview()
            }
        }
    }

    private fun playPreview(file: File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val p = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            previewTicker?.cancel()
                            _state.update { it.copy(isPreviewPlaying = false, previewPositionMs = 0L) }
                        }
                        prepare()
                    }
                    player = p
                    p.start()
                    _state.update {
                        it.copy(
                            isPreviewPlaying = true,
                            previewPositionMs = 0L,
                            previewDurationMs = p.duration.toLong().coerceAtLeast(0L),
                        )
                    }
                    true
                }.getOrDefault(false)
            }
            if (!ok) {
                _state.update { it.copy(error = "could not play recording") }
                return@launch
            }
            previewTicker = viewModelScope.launch {
                while (true) {
                    delay(250)
                    val p = player ?: break
                    _state.update { it.copy(previewPositionMs = p.currentPosition.toLong().coerceAtLeast(0L)) }
                }
            }
        }
    }

    private fun pausePreview() {
        runCatching { player?.pause() }
        previewTicker?.cancel()
        _state.update { it.copy(isPreviewPlaying = false) }
    }

    private fun resumePreview() {
        runCatching { player?.start() }
        _state.update { it.copy(isPreviewPlaying = true) }
        previewTicker?.cancel()
        previewTicker = viewModelScope.launch {
            while (true) {
                delay(250)
                val p = player ?: break
                _state.update { it.copy(previewPositionMs = p.currentPosition.toLong().coerceAtLeast(0L)) }
            }
        }
    }

    fun seekPreview(positionMs: Long) {
        runCatching { player?.seekTo(positionMs.toInt()) }
        _state.update { it.copy(previewPositionMs = positionMs) }
    }

    fun stopPreview() {
        previewTicker?.cancel()
        runCatching { player?.release() }
        player = null
        _state.update { it.copy(isPreviewPlaying = false, previewPositionMs = 0L) }
    }

    /** Reads the recorded file bytes (for save-to-device / sharing). */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                delay(500)
                _state.update { it.copy(elapsedMs = System.currentTimeMillis() - startedAt) }
            }
        }
    }

    override fun onCleared() {
        disposed.set(true)
        ticker?.cancel()
        previewTicker?.cancel()
        recordingGate.stopAfterDispose(recordingOwner)
        runCatching { player?.release() }
        player = null
    }
}
