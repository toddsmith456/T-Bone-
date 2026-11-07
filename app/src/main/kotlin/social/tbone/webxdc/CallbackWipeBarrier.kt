package social.tbone.webxdc

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Serial wipe callers must not start overlapping non-cancellable deletions.
 * A timed-out platform operation may still be running; retain its completion
 * until a later attempt observes it before starting another deletion. */
internal class CallbackWipeBarrier(private val timeoutMillis: Long = 30_000) {
    private var pending: CompletableDeferred<Unit>? = null

    suspend fun run(start: (() -> Unit) -> Unit) {
        pending?.let { prior ->
            awaitCompletion(prior)
            pending = null
        }
        val current = CompletableDeferred<Unit>()
        pending = current
        try {
            start { current.complete(Unit) }
        } catch (error: Throwable) {
            pending = null
            throw error
        }
        awaitCompletion(current)
        pending = null
    }

    private suspend fun awaitCompletion(operation: CompletableDeferred<Unit>) {
        val finished = withTimeoutOrNull(timeoutMillis) { operation.await(); true } ?: false
        if (!finished) throw IOException("WebView browser-data deletion timed out")
    }
}
