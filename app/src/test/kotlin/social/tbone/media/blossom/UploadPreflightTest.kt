package social.tbone.media.blossom

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadPreflightTest {
    @Test fun blocksOnlyEnabledTorPreflightWhenProxyIsNotDetected() {
        for (tor in listOf(false, true)) for (enabled in listOf(false, true)) for (connected in listOf(false, true)) {
            assertEquals(tor && enabled && !connected, shouldBlockUploadPreflight(tor, enabled, connected))
        }
    }
}
