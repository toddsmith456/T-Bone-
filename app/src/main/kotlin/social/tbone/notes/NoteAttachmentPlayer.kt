package social.tbone.notes

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import social.tbone.toolbox.VoiceRecordingGate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Per-editor playback; the shared wipe gate owns every prepared player. */
internal class NoteAttachmentPlayer(
    private val gate: VoiceRecordingGate,
    private val scope: CoroutineScope,
    private val epoch: Long,
) {
    private val owner = Any()
    private val serial = Mutex()
    private val generation = AtomicLong()
    private val disposed = AtomicBoolean()

    fun play(bytes: ByteArray, onDone: () -> Unit) {
        val request = generation.incrementAndGet()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                serial.withLock {
                    if (disposed.get() || generation.get() != request) return@withLock false
                    try {
                        gate.stopPlayback(owner)
                        gate.startPlayback(owner, epoch) { register ->
                            var instance: MediaPlayer? = null
                            register {
                                instance?.release()
                                scope.launch {
                                    if (!disposed.get() && generation.get() == request) onDone()
                                }
                            }
                            val player = MediaPlayer()
                            instance = player
                            player.setDataSource(NoteAudioSource(bytes))
                            player.setOnCompletionListener { finish(request, onDone) }
                            player.setOnErrorListener { _, _, _ -> finish(request, onDone); true }
                            player.prepare()
                            check(!disposed.get() && generation.get() == request) { "playback no longer needed" }
                            player.start()
                        }
                    } catch (_: Exception) { false }
                }
            }
            if (!ok && !disposed.get() && generation.get() == request) onDone()
        }
    }

    private fun finish(request: Long, onDone: () -> Unit) {
        scope.launch {
            if (!disposed.get() && generation.compareAndSet(request, request + 1)) {
                onDone()
                withContext(Dispatchers.IO) {
                    serial.withLock {
                        if (generation.get() == request + 1) {
                            try { gate.stopPlayback(owner) } catch (error: Exception) { timber.log.Timber.w(error, "Playback release failed") }
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        val request = generation.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            serial.withLock {
                if (generation.get() == request) {
                    try { gate.stopPlayback(owner) } catch (error: Exception) { timber.log.Timber.w(error, "Playback release failed") }
                }
            }
        }
    }

    fun dispose() {
        disposed.set(true)
        generation.incrementAndGet()
        gate.stopPlaybackAfterDispose(owner)
    }
}
