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

    @Test
    fun `detects 0_5_2 from the mislabeled 0_5_x builds`() {
        // v0.5.1 shipped versionName 0.5.0 because the release files drifted.
        // Both installed identities must still see 0.5.2 as an update.
        assertTrue(UpdateManager.isNewerVersion("v0.5.2", "0.5.0"))
        assertTrue(UpdateManager.isNewerVersion("v0.5.2", "0.5.1"))
        assertFalse(UpdateManager.isNewerVersion("0.5.2", "0.5.2"))
        assertTrue(UpdateManager.isNewerVersion("0.5.10", "0.5.2"))
    }
}
