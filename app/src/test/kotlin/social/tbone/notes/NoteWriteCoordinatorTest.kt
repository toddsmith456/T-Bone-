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
}
