package social.tbone.webxdc

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CallbackWipeBarrierTest {
    @Test fun timedOutDeletionMustFinishBeforeAnotherCanStart() = runTest {
        val barrier = CallbackWipeBarrier(100)
        val callbacks = mutableListOf<() -> Unit>()
        val first = async { runCatching { barrier.run { callbacks += it } } }
        runCurrent()
        assertEquals(1, callbacks.size)
        advanceTimeBy(100); runCurrent()
        assertTrue(first.await().exceptionOrNull() is IOException)

        val retryWhilePending = async { runCatching { barrier.run { callbacks += it } } }
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertTrue(retryWhilePending.await().exceptionOrNull() is IOException)
        assertEquals("must not overlap unfinished platform deletion", 1, callbacks.size)

        callbacks.single().invoke()
        val finalRetry = async { runCatching { barrier.run { callbacks += it } } }
        runCurrent()
        assertEquals(2, callbacks.size)
        callbacks.last().invoke()
        runCurrent()
        assertTrue(finalRetry.await().isSuccess)
    }

    @Test fun immediateStartFailureCanBeRetried() = runTest {
        val barrier = CallbackWipeBarrier(100)
        assertTrue(runCatching { barrier.run { throw IllegalStateException("unsupported") } }.isFailure)
        var called = false
        barrier.run { callback -> called = true; callback() }
        assertTrue(called)
    }
}
