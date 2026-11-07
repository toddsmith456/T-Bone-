package social.tbone.webxdc

import org.junit.Assert.*
import org.junit.Test

class XdcBusyTrackerTest {
    @Test fun overlappingRequestsAndWipeDoNotClearNewerWork() {
        val tracker = XdcBusyTracker()
        val first = tracker.start("same")
        val second = tracker.start("same")
        tracker.start("other")
        tracker.finish("same", first)
        assertEquals(setOf("same", "other"), tracker.keys.value)
        tracker.clear()
        val replacement = tracker.start("same")
        tracker.finish("same", second)
        assertEquals(setOf("same"), tracker.keys.value)
        tracker.finish("same", replacement)
        assertTrue(tracker.keys.value.isEmpty())
    }
}
