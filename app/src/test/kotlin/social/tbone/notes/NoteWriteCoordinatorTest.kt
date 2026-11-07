package social.tbone.notes

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NoteWriteCoordinatorTest {
    @Test fun submittedWorkSurvivesCallerCancellationAndImmediateReadWaits() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var stored = "old"
            val write = coordinator.submit { started.complete(Unit); release.await(); stored = "new" }
            started.await()
            val caller = launch { write.await() }
            caller.cancelAndJoin()
            val read = coordinator.submit { stored }
            assertFalse(read.isCompleted)
            release.complete(Unit)
            assertEquals("new", read.await())
        } finally { owner.cancel() }
    }

    @Test fun writesAndDeletionRemainOrdered() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            var stored: String? = null
            val first = coordinator.submit { stored = "first" }
            val second = coordinator.submit { stored = "second" }
            val delete = coordinator.submit { stored = null }
            val read = coordinator.submit { stored }
            first.await(); second.await(); delete.await()
            assertNull(read.await())
        } finally { owner.cancel() }
    }

    @Test fun wipeBarrierDrainsPriorWorkAndRejectsNewWorkUntilResumed() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val write = coordinator.submit { started.complete(Unit); release.await(); "saved" }
            started.await()
            val pause = async(start = CoroutineStart.UNDISPATCHED) { coordinator.pauseAndDrain() }
            assertFalse(pause.isCompleted)
            val rejected = coordinator.submit { "should not write" }
            try { rejected.await(); fail("write accepted during wipe") } catch (_: IOException) { }
            release.complete(Unit)
            assertEquals("saved", write.await())
            pause.await()
            coordinator.resumeAfterWipe()
            assertEquals("allowed", coordinator.submit { "allowed" }.await())
        } finally { owner.cancel() }
    }

    @Test fun failedOperationDoesNotPreventLaterWrites() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val failed = coordinator.submit<String> { throw IOException("disk failure") }
            val subsequent = coordinator.submit { "still usable" }
            try { failed.await(); fail("failure was lost") } catch (_: IOException) { }
            assertEquals("still usable", subsequent.await())
        } finally { owner.cancel() }
    }


    @Test fun wipesAreSerializedAndFailureKeepsQueueBlockedUntilFullRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val toolboxStarted = CompletableDeferred<Unit>()
            val full = async {
                coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) {
                    coordinator.pauseAndDrain()
                    started.complete(Unit)
                    release.await()
                    false
                }
            }
            started.await()
            val toolbox = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.runWipe(NoteWriteCoordinator.WipeKind.TOOLBOX) {
                    toolboxStarted.complete(Unit)
                    assertTrue(coordinator.hasFailedAppWipe())
                    true
                }
            }
            assertFalse(toolboxStarted.isCompleted)
            release.complete(Unit)
            assertFalse(full.await())
            assertTrue(toolbox.await())
            try { coordinator.submit { "blocked" }.await(); fail("failed app wipe was cleared") }
            catch (_: IOException) { }
            assertTrue(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) { coordinator.pauseAndDrain(); true })
            assertEquals("allowed", coordinator.submit { "allowed" }.await())
        } finally { owner.cancel() }
    }

    @Test fun cancelledWipeKeepsWritesBlocked() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val started = CompletableDeferred<Unit>()
            val wipe = launch {
                coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            started.await()
            wipe.cancelAndJoin()
            try { coordinator.submit { "blocked" }.await(); fail("cancelled wipe reopened queue") }
            catch (_: IOException) { }
        } finally { owner.cancel() }
    }


    @Test fun stoppedWorkerRejectsQueuedOperationsInsteadOfLeavingThemHanging() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = NoteWriteCoordinator(owner)
        val started = CompletableDeferred<Unit>()
        coordinator.submit { started.complete(Unit); awaitCancellation() }
        started.await()
        val queued = coordinator.submit { "not reached" }
        owner.cancel()
        withTimeout(3_000) {
            try { queued.await(); fail("queued operation should fail") } catch (_: IOException) { }
        }
    }

    @Test fun taskCancellationDoesNotKillAnActiveWorker() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val cancelled = coordinator.submit<Unit> { throw CancellationException("operation cancelled") }
            try { cancelled.await(); fail("expected cancellation") } catch (_: CancellationException) { }
            assertEquals("next", withTimeout(3_000) { coordinator.submit { "next" }.await() })
        } finally { owner.cancel() }
    }


    @Test fun cancellableOperationStopsWithCallerAndDoesNotStallQueue() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            val started = CompletableDeferred<Unit>()
            val cleaned = CompletableDeferred<Unit>()
            val caller = launch {
                coordinator.runCancellable {
                    try { started.complete(Unit); awaitCancellation() }
                    finally { cleaned.complete(Unit) }
                }
            }
            started.await()
            caller.cancelAndJoin()
            withTimeout(3_000) { cleaned.await() }
            assertEquals("next", withTimeout(3_000) { coordinator.submit { "next" }.await() })
        } finally { owner.cancel() }
    }

    @Test fun availabilityReflectsWipeFailureAndRetry() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner)
            assertTrue(coordinator.available.value)
            assertFalse(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) {
                assertFalse(coordinator.available.value)
                false
            })
            assertFalse(coordinator.available.value)
            assertTrue(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) { true })
            assertTrue(coordinator.available.value)
        } finally { owner.cancel() }
    }

}
