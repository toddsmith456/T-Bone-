package social.tbone.notes

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private class Work(val execute: suspend () -> Unit, val reject: (Throwable) -> Unit)
    private val work = Channel<Work>(Channel.UNLIMITED)
    private var wiping = false
    private val wipeMutex = Mutex()
    private val failedWipes = mutableSetOf<WipeKind>()

    enum class WipeKind { APP, TOOLBOX }

    init {
        scope.launch {
            for (task in work) task.execute()
        }.invokeOnCompletion { cause ->
            synchronized(lock) {
                work.close(cause)
                val failure = IOException("Note writer stopped", cause)
                while (true) (work.tryReceive().getOrNull() ?: break).reject(failure)
            }
        }
    }

    fun <T> submit(action: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        synchronized(lock) {
            if (wiping) {
                result.completeExceptionally(IOException("Notes are unavailable during a wipe"))
                return result
            }
            val sent = work.trySend(Work(execute = {
                try {
                    result.complete(action())
                } catch (cancelled: CancellationException) {
                    result.completeExceptionally(cancelled)
                    if (!currentCoroutineContext().isActive) throw cancelled
                } catch (error: Exception) {
                    Timber.e(error, "Note operation failed")
                    result.completeExceptionally(error)
                } catch (fatal: Throwable) {
                    result.completeExceptionally(fatal)
                    throw fatal
                }
            }, reject = { result.completeExceptionally(it) }))
            if (sent.isFailure) result.completeExceptionally(IOException("Note writer is unavailable"))
        }
        return result
    }

    /** Serialize both wipe flows and retain the gate after a failed wipe. */
    suspend fun runWipe(kind: WipeKind, action: suspend () -> Boolean): Boolean = wipeMutex.withLock {
        synchronized(lock) { wiping = true }
        var succeeded = false
        try {
            succeeded = action()
            succeeded
        } finally {
            synchronized(lock) {
                if (succeeded) {
                    if (kind == WipeKind.APP) failedWipes.clear() else failedWipes.remove(kind)
                } else failedWipes.add(kind)
                wiping = failedWipes.isNotEmpty()
            }
        }
    }

    fun hasFailedAppWipe(): Boolean = synchronized(lock) { WipeKind.APP in failedWipes }

    suspend fun pauseAndDrain() {
        val drained = CompletableDeferred<Unit>()
        synchronized(lock) {
            wiping = true
            if (work.trySend(Work(execute = { drained.complete(Unit) }, reject = { drained.completeExceptionally(it) })).isFailure)
                drained.completeExceptionally(IOException("Note writer is unavailable"))
        }
        drained.await()
    }

    fun resumeAfterWipe() = synchronized(lock) { wiping = false }
}
