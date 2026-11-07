package social.tbone.security

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WipeStepsTest {
    @Test fun recordsFailureAndStillRunsOtherCleanup() = runBlocking {
        val failures = mutableListOf<String>()
        val steps = WipeSteps { label, _ -> failures.add(label) }
        var secondRan = false
        steps.run("first") { error("storage unavailable") }
        steps.run("second") { secondRan = true }
        assertTrue(steps.failed)
        assertTrue(secondRan)
        assertEquals(listOf("first"), failures)
    }

    @Test fun cancellationIsNotSwallowedOrReportedAsAnOrdinaryFailure() = runBlocking {
        val failures = mutableListOf<String>()
        val steps = WipeSteps { label, _ -> failures.add(label) }
        try {
            steps.run("cancelled") { throw kotlinx.coroutines.CancellationException("stop") }
            fail("cancellation should propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertFalse(steps.failed)
        assertTrue(failures.isEmpty())
    }

    @Test fun failuresAccumulateWithoutSkippingIndependentSteps() = runBlocking {
        val failures = mutableListOf<String>()
        val attempted = mutableListOf<String>()
        val steps = WipeSteps { label, _ -> failures.add(label) }
        steps.run("one") { attempted.add("one"); error("failure one") }
        steps.run("two") { attempted.add("two"); error("failure two") }
        steps.run("three") { attempted.add("three") }
        assertEquals(listOf("one", "two", "three"), attempted)
        assertEquals(listOf("one", "two"), failures)
        assertTrue(steps.failed)
    }

}
