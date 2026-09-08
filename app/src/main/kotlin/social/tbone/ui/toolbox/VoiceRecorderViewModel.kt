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
) : ViewModel() {

    private val anonymizer = VoiceAnonymizer()

    private val _state = MutableStateFlow(VoiceRecorderState())
    val state: StateFlow<VoiceRecorderState> = _state.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var startedAt: Long = 0L
    private var ticker: Job? = null

    private var player: MediaPlayer? = null
    private var previewTicker: Job? = null

    fun setSpeed(value: Float) {
        if (_state.value.isRecording) return
        _state.update { it.copy(speed = value) }
    }

    fun setPitch(value: Float) {
        if (_state.value.isRecording) return
        _state.update { it.copy(pitch = value) }
    }

    fun setDrive(value: Float) {
        if (_state.value.isRecording) return
        _state.update { it.copy(drive = value) }
    }

    fun setQuality(quality: VoiceQuality) {
        if (_state.value.isRecording) return // can't change mid-recording
        _state.update { it.copy(quality = quality) }
    }

    /** Begins a new recording into [dir]. Assumes microphone permission granted. */
    fun startRecording(dir: File) {
        if (_state.value.isRecording) return
        viewModelScope.launch {
            val q = _state.value.quality
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val file = File(dir, "voice_${System.currentTimeMillis()}.m4a")
                    val r = MediaRecorder(context).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioSamplingRate(q.sampleRate)
                        setAudioEncodingBitRate(q.bitRate)
                        setOutputFile(file.absolutePath)
                        prepare()
                        start()
                    }
                    recorder = r
                    startedAt = System.currentTimeMillis()
                    stopPreview()
                    _state.update {
                        it.copy(
                            isRecording = true,
                            isPaused = false,
                            currentFile = file,
                            elapsedMs = 0L,
                            error = null,
                        )
                    }
                    startTicker()
                    true
                }.getOrDefault(false)
            }
            if (!ok) {
                _state.update { it.copy(isRecording = false, error = "could not start recording — check microphone permission") }
            }
        }
    }

    fun pauseRecording() {
        if (!_state.value.isRecording || _state.value.isPaused) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { recorder?.pause(); true }.getOrDefault(false)
            }
            if (ok) {
                ticker?.cancel()
                _state.update { it.copy(isPaused = true) }
            }
        }
    }

    fun resumeRecording() {
        if (!_state.value.isRecording || !_state.value.isPaused) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { recorder?.resume(); true }.getOrDefault(false)
            }
            if (ok) {
                _state.update { it.copy(isPaused = false) }
                startTicker()
            }
        }
    }

    /** Stops recording and finalizes the file. Share/save options appear after this. */
    fun stopRecording() {
        if (!_state.value.isRecording) return
        ticker?.cancel()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    recorder?.apply {
                        runCatching { stop() }
                        release()
                    }
                    recorder = null
                }
            }
            // Starting a new recording after this resets the anonymized state.
            _state.update { it.copy(isRecording = false, isPaused = false, anonymousFile = null, isAnonymous = false) }
        }
    }

    /** Renders the recording completely anonymous (irreversible). */
    fun anonymize() {
        val src = _state.value.currentFile ?: return
        if (_state.value.isRecording || _state.value.isPaused) return
        val speed = _state.value.speed
        val pitch = _state.value.pitch
        val drive = _state.value.drive
        viewModelScope.launch {
            val anon = withContext(Dispatchers.IO) {
                runCatching { anonymizer.anonymize(src, speed, pitch, drive) }.getOrNull()
            }
            if (anon != null) {
                stopPreview()
                _state.update {
                    it.copy(
                        anonymousFile = anon,
                        isAnonymous = true,
                        isPreviewPlaying = false,
                        previewPositionMs = 0L,
                        previewDurationMs = 0L,
                        error = null,
                    )
                }
            } else {
                _state.update { it.copy(error = "anonymization failed") }
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
    suspend fun readBytes(file: File): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrNull()
        }

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
        ticker?.cancel()
        previewTicker?.cancel()
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.release() }
        player = null
    }
}
