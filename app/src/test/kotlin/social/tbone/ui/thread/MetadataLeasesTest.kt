package social.tbone.ui.thread

import org.junit.Assert.*
import org.junit.Test

class MetadataLeasesTest {
    @Test fun duplicatesAreSuppressedUntilTheOwningSubscriptionCloses() {
        val leases = MetadataLeases()
        assertEquals(listOf("a", "b"), leases.claim("one", listOf("a", "a", "b")))
        assertEquals(listOf("c"), leases.claim("two", listOf("a", "c")))
        assertTrue(leases.contains("one"))
        assertTrue(leases.release("one"))
        assertFalse(leases.release("one"))
        assertEquals(listOf("a", "b"), leases.claim("three", listOf("a", "b", "c")))
        assertTrue(leases.release("two"))
        assertEquals(listOf("c"), leases.claim("four", listOf("c")))
    }

    @Test fun completedRequestsCancelTimersAndLateTimerCannotLeak() = kotlinx.coroutines.test.runTest {
        val leases = MetadataLeases()
        leases.claim("one", listOf("a"))
        val timer = kotlinx.coroutines.Job()
        assertTrue(leases.attachTimeout("one", timer))
        assertTrue(leases.release("one"))
        assertTrue(timer.isCancelled)
        val late = kotlinx.coroutines.Job()
        assertFalse(leases.attachTimeout("one", late))
        assertTrue(late.isCancelled)
        assertEquals(listOf("a"), leases.claim("two", listOf("a")))
    }

    @Test fun concurrentClaimsNeverRequestTheSameAuthorTwice() {
        val leases = MetadataLeases()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val results = (0 until 32).map { n -> pool.submit<List<String>> {
                start.await(); leases.claim("id-$n", listOf("same"))
            } }
            start.countDown()
            assertEquals(1, results.sumOf { it.get(5, java.util.concurrent.TimeUnit.SECONDS).size })
        } finally { pool.shutdownNow() }
    }
}
