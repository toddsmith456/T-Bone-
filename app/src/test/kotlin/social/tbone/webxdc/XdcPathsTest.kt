package social.tbone.webxdc

import org.junit.Assert.*
import org.junit.Test

class XdcPathsTest {
    @Test fun onlyContentAddressedIdsAreAccepted() {
        assertTrue(validXdcId("0123456789abcdef0123456789abcdef"))
        listOf("../secret", "/absolute", "abc", "F".repeat(32), "g".repeat(32)).forEach {
            assertFalse(validXdcId(it))
        }
    }
    @Test fun iconMustBeKnownRootLevelName() {
        assertTrue(validXdcIconName("ICON.PNG"))
        assertFalse(validXdcIconName("../icon.png"))
        assertFalse(validXdcIconName("sub/icon.png"))
        assertFalse(validXdcIconName("icon.png/../secret"))
    }
}
