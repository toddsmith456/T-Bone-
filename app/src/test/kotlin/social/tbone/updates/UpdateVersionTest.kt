package social.tbone.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun `compares release versions numerically`() {
        assertTrue(UpdateManager.isNewerVersion("v0.4.1", "0.4.0"))
        assertTrue(UpdateManager.isNewerVersion("0.10.0", "0.4.1"))
        assertFalse(UpdateManager.isNewerVersion("0.4.0", "0.4.0"))
        assertFalse(UpdateManager.isNewerVersion("release-preview", "0.4.0"))
    }
}
