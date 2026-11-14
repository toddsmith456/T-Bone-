package social.tbone.ui.feed

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Guarantees that a spinning feed eventually either shows content or says why.
 *
 * ### The bug this exists to make impossible
 * Every loader had its own "safety net" timeout, and each one was written as
 * `if (!feedSettled) { feedSettled = true … }` — which stops the loader from
 * waiting for a relay, but does **not** clear `isLoading`. On the follow feed the
 * net cleared the spinner as a separate, later statement; on clock, suggested and
 * global it simply settled the feed and left `isLoading` true. So any path that
 * settled without producing a result left the spinner turning forever, with every
 * relay showing connected, which is exactly what the user reported.
 *
 * There is a second, quieter version of the same failure: a load is torn down and
 * restarted before it finishes (a relay-set change, a config flow re-emitting, the
 * account flow re-emitting). The first load's safety net belongs to a load that no
 * longer exists, and the second load's net is fifteen seconds away. Fifteen seconds
 * of spinner is indistinguishable from a hang.
 *
 * ### What this does instead
 * One object per load. It records that a load is in flight and arms a single
 * timeout. When the load finishes, [finish] disarms it. If the timeout fires first,
 * [onTimeout] runs exactly once and the caller must end the loading state — there
 * is no way to disarm the spinner without deciding what to show instead, because
 * the timeout callback is required.
 *
 * [finish] is idempotent and [cancel] is permanent for that generation, so a stale
 * timer from a superseded load can never publish over the top of a newer one.
 */
internal class LoadWatchdog(
    private val scope: CoroutineScope,
    /** Shown when nothing arrived in time. Never a bare spinner. */
    private val emptyMessage: String,
) {

    private var timeoutJob: Job? = null

    /** Guards against a stale timer publishing after a newer load started. */
    private var generation: Long = 0L

    /**
     * Arms the timeout for a new load, cancelling any previous one.
     *
     * @param onTimeout called on the main dispatcher's scope when the load has
     *   not finished. [timedOutWithNothing] is true when the feed is still empty,
     *   so the caller can choose between an error and an empty state.
     */
    fun start(
        timeoutMs: Long,
        isEmpty: () -> Boolean = { true },
        onTimeout: (timedOutWithNothing: Boolean) -> Unit,
    ) {
        timeoutJob?.cancel()
        generation += 1
        val mine = generation
        timeoutJob = scope.launch {
            delay(timeoutMs)
            if (mine != generation) return@launch
            onTimeout(isEmpty())
        }
    }

    /**
     * The load finished (settled, failed, or was blocked).
     *
     * Cancelling is safe to call from any branch and any number of times.
     */
    fun finish() {
        timeoutJob?.cancel()
        timeoutJob = null
        // Bumping the generation also invalidates a timer that already fired and
        // is sitting in its callback, so it cannot publish a stale message.
        generation += 1
    }

    /** Why an empty feed is empty, in the app's own words. */
    fun emptyMessage(): String = emptyMessage
}
