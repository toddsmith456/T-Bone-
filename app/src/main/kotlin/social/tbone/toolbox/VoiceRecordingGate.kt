package social.tbone.toolbox

import social.tbone.notes.NoteWriteCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes recorder lifecycle with wipe shutdown. The recorder remains
 * registered until release succeeds, so a failed shutdown can be retried. */
@Singleton
class VoiceRecordingGate @Inject constructor(
    private val writes: NoteWriteCoordinator,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var owner: Any? = null
    private var close: ((Boolean) -> Unit)? = null
    private val playbacks = java.util.IdentityHashMap<Any, () -> Unit>()

    fun start(token: Any, epoch: Long, create: () -> (Boolean) -> Unit): Boolean = synchronized(lock) {
        if (close != null || epoch == Long.MIN_VALUE || epoch != writes.currentWipeEpoch()) return@synchronized false
        val closer = create()
        owner = token
        close = closer
        true
    }

    /** Register cleanup before touching the microphone or creating sensitive files.
     * If initialization fails, attempt disposal; retain failed cleanup for wipe retry. */
    fun startRegistered(token: Any, epoch: Long, setup: (register: ((Boolean) -> Unit) -> Unit) -> Unit): Boolean = synchronized(lock) {
        if (close != null || epoch == Long.MIN_VALUE || epoch != writes.currentWipeEpoch()) return@synchronized false
        try {
            setup { closer ->
                check(close == null) { "recorder cleanup already registered" }
                owner = token
                close = closer
            }
            check(owner === token && close != null) { "recorder cleanup not registered" }
            true
        } catch (failure: Throwable) {
            if (owner === token) {
                try { stopLocked(true) } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            }
            throw failure
        }
    }

    fun runIfOwned(token: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (owner !== token || close == null) return@synchronized false
        action()
        true
    }

    fun stop(token: Any, discard: Boolean = false) = synchronized(lock) {
        if (owner === token) stopLocked(discard)
    }

    /** Disposal runs on Main; recorder stop/release may block, so finish on IO. */
    fun stopAfterDispose(token: Any) {
        scope.launch(Dispatchers.IO) {
            try { stop(token, discard = true) }
            catch (error: Exception) { timber.log.Timber.w(error, "Recorder release failed") }
        }
    }

    /** Register playback before preparing it, so a wipe can stop and drain it. */
    fun startPlayback(token: Any, epoch: Long, setup: (register: (() -> Unit) -> Unit) -> Unit): Boolean = synchronized(lock) {
        if (epoch == Long.MIN_VALUE || epoch != writes.currentWipeEpoch() || playbacks.containsKey(token)) return@synchronized false
        try {
            setup { closer ->
                check(!playbacks.containsKey(token)) { "playback cleanup already registered" }
                playbacks[token] = closer
            }
            check(playbacks.containsKey(token)) { "playback cleanup not registered" }
            true
        } catch (failure: Throwable) {
            try { stopPlaybackLocked(token) } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
    }

    fun runIfPlaybackOwned(token: Any, action: () -> Unit): Boolean = synchronized(lock) {
        if (!playbacks.containsKey(token)) return@synchronized false
        action()
        true
    }

    fun stopPlayback(token: Any) = synchronized(lock) { stopPlaybackLocked(token) }

    fun stopPlaybackAfterDispose(token: Any) {
        scope.launch(Dispatchers.IO) {
            try { stopPlayback(token) }
            catch (error: Exception) { timber.log.Timber.w(error, "Playback release failed") }
        }
    }

    private fun stopPlaybackLocked(token: Any) {
        val closer = playbacks[token] ?: return
        closer()
        playbacks.remove(token)
    }

    fun stopForWipe() = synchronized(lock) {
        var failure: Exception? = null
        try { stopLocked(true) } catch (error: Exception) { failure = error }
        playbacks.keys.toList().forEach { token ->
            try { stopPlaybackLocked(token) } catch (error: Exception) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun stopLocked(discard: Boolean) {
        val closer = close ?: return
        closer(discard)
        close = null
        owner = null
    }
}
