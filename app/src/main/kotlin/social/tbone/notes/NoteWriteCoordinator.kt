package social.tbone.notes

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class NoteWriteCoordinator internal constructor(scope: CoroutineScope, private val wipeState: WipeStateStore) {
    @Inject constructor(scope: CoroutineScope, wipeState: PersistentWipeState) : this(scope, wipeState as WipeStateStore)
    internal constructor(scope: CoroutineScope) : this(scope, InMemoryWipeState())
    private val lock = Any()
    private class Work(val execute: suspend () -> Unit, val reject: (Throwable) -> Unit)
    private val work = Channel<Work>(Channel.UNLIMITED)
    private val failedWipes = wipeState.read().toMutableSet()
    private var wiping = failedWipes.isNotEmpty()
    private val _available = MutableStateFlow(!wiping)
    val available = _available.asStateFlow()
    private val _pendingAppWipe = MutableStateFlow(WipeKind.APP in failedWipes || WipeKind.UNKNOWN in failedWipes)
    val pendingAppWipe = _pendingAppWipe.asStateFlow()
    private val _invalidWipeState = MutableStateFlow(WipeKind.UNKNOWN in failedWipes)
    val invalidWipeState = _invalidWipeState.asStateFlow()
    private val wipeMutex = Mutex()

    enum class WipeKind { APP, TOOLBOX, UNKNOWN }

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

    /** Long operations retain cancellation from the caller, unlike editor saves. */
    suspend fun <T> runCancellable(action: suspend () -> T): T {
        val caller = checkNotNull(currentCoroutineContext()[Job])
        return submit { withContext(caller) { action() } }.await()
    }

    /** Close the gate and durably record intent before launching wipe work. */
    fun requestWipe(kind: WipeKind): Boolean = synchronized(lock) {
        require(kind != WipeKind.UNKNOWN) { "Unknown wipe is not an executable operation" }
        // Never turn a corrupt/unknown marker into an automatic destructive wipe.
        if (WipeKind.UNKNOWN in failedWipes) return@synchronized false
        failedWipes.add(kind)
        wiping = true
        _available.value = false
        _pendingAppWipe.value = WipeKind.APP in failedWipes || WipeKind.UNKNOWN in failedWipes
        _invalidWipeState.value = WipeKind.UNKNOWN in failedWipes
        wipeState.write(failedWipes.toSet())
    }

    fun pendingWipes(): Set<WipeKind> = synchronized(lock) { failedWipes.toSet() }

    /** Serialize wipes; retain a durable, fail-closed gate after failure/cancellation. */
    suspend fun runWipe(kind: WipeKind, action: suspend () -> Boolean): Boolean = wipeMutex.withLock {
        if (!requestWipe(kind)) return@withLock false
        var completed = false
        try {
            completed = action()
            if (completed) synchronized(lock) {
                val remaining = if (kind == WipeKind.APP) emptySet() else failedWipes - kind
                if (wipeState.write(remaining)) {
                    failedWipes.clear()
                    failedWipes.addAll(remaining)
                } else completed = false
            }
            completed
        } finally {
            synchronized(lock) {
                // The marker was written before executing action and is cleared
                // only after successful cleanup; interruption remains fail-closed.
                wiping = failedWipes.isNotEmpty()
                _available.value = !wiping
                _pendingAppWipe.value = WipeKind.APP in failedWipes || WipeKind.UNKNOWN in failedWipes
                _invalidWipeState.value = WipeKind.UNKNOWN in failedWipes
            }
        }
    }

    fun hasFailedAppWipe(): Boolean = synchronized(lock) { WipeKind.APP in failedWipes || WipeKind.UNKNOWN in failedWipes }

    suspend fun pauseAndDrain() {
        val drained = CompletableDeferred<Unit>()
        synchronized(lock) {
            wiping = true
            _available.value = false
            if (work.trySend(Work(execute = { drained.complete(Unit) }, reject = { drained.completeExceptionally(it) })).isFailure)
                drained.completeExceptionally(IOException("Note writer is unavailable"))
        }
        drained.await()
    }

    internal fun resumeAfterWipe() = synchronized(lock) {
        check(failedWipes.isEmpty()) { "Cannot resume while a wipe remains pending" }
        wiping = false; _available.value = true
    }
}
