package social.tbone.notes

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application-scoped FIFO for note reads, text writes, and deletes. A submitted
 * write outlives the editor/ViewModel that submitted it, and an immediate
 * reopen waits behind pending writes. The wipe barrier rejects new operations
 * and drains existing work before the vault is cleared.
 */
@Singleton
class NoteWriteCoordinator @Inject constructor(scope: CoroutineScope) {
    private val lock = Any()
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var wiping = false

    init {
        scope.launch {
            for (task in work) task()
        }.invokeOnCompletion { cause ->
            work.close(cause)
        }
    }

    fun <T> submit(action: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        synchronized(lock) {
            if (wiping) {
                result.completeExceptionally(IOException("Notes are unavailable during a wipe"))
                return result
            }
            val sent = work.trySend {
                try {
                    result.complete(action())
                } catch (cancelled: CancellationException) {
                    result.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "Note operation failed")
                    result.completeExceptionally(error)
                }
            }
            if (sent.isFailure) result.completeExceptionally(IOException("Note writer is unavailable"))
        }
        return result
    }

    suspend fun pauseAndDrain() {
        val drained = CompletableDeferred<Unit>()
        synchronized(lock) {
            wiping = true
            if (work.trySend { drained.complete(Unit) }.isFailure)
                drained.completeExceptionally(IOException("Note writer is unavailable"))
        }
        drained.await()
    }

    fun resumeAfterWipe() = synchronized(lock) { wiping = false }
}
