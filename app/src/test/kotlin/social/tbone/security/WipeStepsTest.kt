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
}
