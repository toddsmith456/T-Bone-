package social.tbone.di

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Proxy

class TorStartupPolicyTest {
    @Test fun unknownSettingsNeverChooseDirectTransport() {
        assertTrue(startupRequiresTor(false, false))
        assertTrue(startupRequiresTor(false, true))
        assertFalse(startupRequiresTor(true, false))
        assertTrue(startupRequiresTor(true, true))
    }

    @Test fun bootstrapMediaClientUsesAProxy() {
        // This test must not mutate the process-wide provider. Other tests do
        // not replace it, so its initialization contract is checked directly.
        assertEquals(Proxy.Type.HTTP, ImageClientProvider.client.proxy?.type())
    }
}
