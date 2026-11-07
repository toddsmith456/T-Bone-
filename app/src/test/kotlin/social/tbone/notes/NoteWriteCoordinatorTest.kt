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


    @Test fun pendingWipeSurvivesCoordinatorRecreationAndRequiresMatchingRetry() = runBlocking {
        val state = InMemoryWipeState()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = NoteWriteCoordinator(firstScope, state)
        assertTrue(first.requestWipe(NoteWriteCoordinator.WipeKind.APP))
        firstScope.cancel()
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val second = NoteWriteCoordinator(secondScope, state)
            assertFalse(second.available.value)
            assertTrue(second.pendingAppWipe.value)
            assertTrue(second.hasFailedAppWipe())
            assertTrue(second.runWipe(NoteWriteCoordinator.WipeKind.TOOLBOX) { true })
            assertFalse(second.available.value)
            assertTrue(second.runWipe(NoteWriteCoordinator.WipeKind.APP) { second.pauseAndDrain(); true })
            assertTrue(second.available.value)
            assertFalse(second.pendingAppWipe.value)
            assertTrue(state.read().isEmpty())
        } finally { secondScope.cancel() }
    }

    @Test fun markerWriteFailureNeverRunsCleanupOrReopensGate() = runBlocking {
        val state = object : WipeStateStore {
            override fun read() = emptySet<NoteWriteCoordinator.WipeKind>()
            override fun write(pending: Set<NoteWriteCoordinator.WipeKind>) = false
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner, state)
            var actionRan = false
            assertFalse(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) { actionRan = true; true })
            assertFalse(actionRan)
            assertFalse(coordinator.available.value)
            assertTrue(coordinator.pendingAppWipe.value)
        } finally { owner.cancel() }
    }

    @Test fun markerClearFailureKeepsGateClosed() = runBlocking {
        val state = object : WipeStateStore {
            var saved = emptySet<NoteWriteCoordinator.WipeKind>()
            override fun read() = saved
            override fun write(pending: Set<NoteWriteCoordinator.WipeKind>): Boolean {
                if (pending.isEmpty()) return false
                saved = pending.toSet()
                return true
            }
        }
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner, state)
            assertFalse(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) { true })
            assertFalse(coordinator.available.value)
            assertEquals(setOf(NoteWriteCoordinator.WipeKind.APP), state.read())
        } finally { owner.cancel() }
    }


    @Test fun unknownRecoveryMarkerBlocksWithoutStartingDestruction() = runBlocking {
        val state = InMemoryWipeState()
        state.write(setOf(NoteWriteCoordinator.WipeKind.UNKNOWN))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(owner, state)
            var ran = false
            assertTrue(coordinator.pendingAppWipe.value)
            assertTrue(coordinator.invalidWipeState.value)
            assertFalse(coordinator.runWipe(NoteWriteCoordinator.WipeKind.APP) { ran = true; true })
            assertFalse(ran)
            assertEquals(setOf(NoteWriteCoordinator.WipeKind.UNKNOWN), state.read())
        } finally { owner.cancel() }
    }

}
