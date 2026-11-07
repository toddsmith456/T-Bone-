package social.tbone.toolbox

import kotlinx.coroutines.*
import social.tbone.notes.NoteWriteCoordinator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoiceRecordingGateTest {
    @Test fun wipeWaitsForStartingRecorderAndClosesIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(coordinator, scope)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stopped = AtomicInteger(0)
            val epoch = coordinator.currentWipeEpoch()
            val creation = async(Dispatchers.IO) {
                gate.start(Any(), epoch) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    val closer: (Boolean) -> Unit = { discard -> if (discard) stopped.incrementAndGet() }
                    closer
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.requestWipe(NoteWriteCoordinator.WipeKind.TOOLBOX))
            val stopping = async(Dispatchers.IO) { gate.stopForWipe() }
            release.countDown()
            assertTrue(creation.await())
            stopping.await()
            assertEquals(1, stopped.get())
            assertFalse(gate.start(Any(), epoch) { fail("stale start executed"); { _ -> } })
        } finally { scope.cancel() }
    }

    @Test fun failedReleaseRemainsRegisteredForRetryAndOwnerIsIsolated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(coordinator, scope)
            val owner = Any()
            var releases = 0
            assertTrue(gate.start(owner, coordinator.currentWipeEpoch()) {
                { _ -> if (++releases == 1) error("release failed") }
            })
            gate.stop(Any())
            assertEquals(0, releases)
            try { gate.stopForWipe(); fail("failure was swallowed") } catch (_: IllegalStateException) { }
            assertFalse(gate.start(Any(), coordinator.currentWipeEpoch()) { { _ -> } })
            gate.stopForWipe()
            assertEquals(2, releases)
            assertTrue(gate.start(Any(), coordinator.currentWipeEpoch()) { { _ -> } })
        } finally { scope.cancel() }
    }

    @Test fun disposedRecorderClosesAsynchronouslyAndCannotCloseAnotherOwner() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val coordinator = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(coordinator, scope)
            val owner = Any()
            val closed = CompletableDeferred<Boolean>()
            assertTrue(gate.start(owner, coordinator.currentWipeEpoch()) {
                { discard -> closed.complete(discard); Unit }
            })
            gate.stopAfterDispose(Any())
            assertFalse(closed.isCompleted)
            gate.stopAfterDispose(owner)
            assertTrue(withTimeout(3_000) { closed.await() })
        } finally { scope.cancel() }
    }


    @Test fun failedInitializationRetainsCleanupWhenReleaseFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writes = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(writes, scope)
            var releaseAttempts = 0
            var deletes = 0
            try {
                gate.startRegistered(Any(), writes.currentWipeEpoch()) { register ->
                    val cleanup = RecordingCleanup(
                        stop = { fail("not started") },
                        release = { if (++releaseAttempts == 1) error("injected") },
                        delete = { deletes++ },
                    )
                    register { discard -> cleanup.close(discard); Unit }
                    error("initialization failed")
                }
                fail("failure was swallowed")
            } catch (error: IllegalStateException) {
                assertEquals("initialization failed", error.message)
                assertEquals(1, error.suppressed.size)
            }
            assertEquals(1, releaseAttempts)
            assertEquals(0, deletes)
            assertFalse(gate.start(Any(), writes.currentWipeEpoch()) { { _ -> } })
            gate.stopForWipe()
            assertEquals(2, releaseAttempts)
            assertEquals(1, deletes)
        } finally { scope.cancel() }
    }

    @Test fun deletionFailureRetriesWithoutStoppingOrReleasingTwice() {
        var stops = 0
        var releases = 0
        var deletions = 0
        val cleanup = RecordingCleanup(
            stop = { stops++ }, release = { releases++ },
            delete = { if (++deletions == 1) error("injected deletion failure") },
        )
        cleanup.markStarted()
        try { cleanup.close(true); fail("failure was swallowed") } catch (_: IllegalStateException) { }
        assertFalse(cleanup.close(true))
        assertEquals(1, stops)
        assertEquals(1, releases)
        assertEquals(2, deletions)
    }

    @Test fun unfinalizableRecordingIsDeletedEvenOnNormalStop() {
        var deleted = false
        val cleanup = RecordingCleanup(
            stop = { error("recording too short") }, release = {}, delete = { deleted = true },
        )
        cleanup.markStarted()
        assertFalse(cleanup.close(false))
        assertTrue(deleted)
    }

    @Test fun wipeStopsAllPlaybackAndRetainsFailedCleanupForRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writes = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(writes, scope)
            val one = Any(); val two = Any()
            var first = 0; var second = 0
            assertTrue(gate.startPlayback(one, writes.currentWipeEpoch()) { register ->
                register { if (++first == 1) error("injected failure") }
            })
            assertTrue(gate.startPlayback(two, writes.currentWipeEpoch()) { register ->
                register { second++ }
            })
            writes.requestWipe(NoteWriteCoordinator.WipeKind.TOOLBOX)
            try { gate.stopForWipe(); fail("failure was swallowed") } catch (_: IllegalStateException) { }
            assertEquals(1, second)
            assertFalse(gate.startPlayback(Any(), writes.currentWipeEpoch()) { fail("started during wipe") })
            gate.stopForWipe()
            assertEquals(2, first)
            assertEquals(1, second)
        } finally { scope.cancel() }
    }

    @Test fun failedPlaybackSetupReleasesPartialPlayer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val writes = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(writes, scope)
            var closed = false
            try {
                gate.startPlayback(Any(), writes.currentWipeEpoch()) { register ->
                    register { closed = true }
                    error("prepare failed")
                }
                fail("failure was swallowed")
            } catch (_: IllegalStateException) { }
            assertTrue(closed)
        } finally { scope.cancel() }
    }

    @Test fun renderingFinishesBeforeWipeAndOldEpochCannotRecreateFile() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = kotlin.io.path.createTempDirectory("voice-wipe").toFile()
        try {
            val writes = NoteWriteCoordinator(scope)
            val gate = VoiceRecordingGate(writes, scope)
            val epoch = writes.currentWipeEpoch()
            val rendering = CompletableDeferred<Unit>()
            val continueRender = CompletableDeferred<Unit>()
            val output = java.io.File(dir, "voice.wav")
            val render = async(Dispatchers.IO) {
                writes.runCancellableAtEpoch(epoch) {
                    rendering.complete(Unit)
                    continueRender.await()
                    output.writeText("sensitive")
                }
            }
            rendering.await()
            val wiped = async(Dispatchers.IO) {
                writes.runWipe(NoteWriteCoordinator.WipeKind.TOOLBOX) {
                    gate.stopForWipe()
                    writes.pauseAndDrain()
                    output.delete()
                    true
                }
            }
            withTimeout(3_000) { while (writes.available.value) yield() }
            assertFalse(wiped.isCompleted)
            continueRender.complete(Unit)
            render.await()
            assertTrue(wiped.await())
            assertFalse(output.exists())
            try { writes.runCancellableAtEpoch(epoch) { output.writeText("stale") }; fail("stale render accepted") }
            catch (_: java.io.IOException) { }
            assertFalse(output.exists())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

}
