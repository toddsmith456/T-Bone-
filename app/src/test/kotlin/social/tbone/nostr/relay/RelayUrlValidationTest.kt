package social.tbone.nostr.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayUrlValidationTest {
    @Test
    fun `accepts Citrine loopback and secure relay URLs`() {
        assertTrue(RelayPool.isValidRelayUrl("ws://localhost:4869"))
        assertTrue(RelayPool.isValidRelayUrl("wss://relay.example"))
        assertTrue(RelayConnection.isLocalRelayUrl("ws://localhost:4869"))
        assertTrue(RelayConnection.isLocalRelayUrl("ws://127.0.0.1:4869"))
        assertTrue(RelayConnection.isLocalRelayUrl("ws://[::1]:4869"))
    }

    @Test
    fun `rejects non relay schemes and credentialed URLs`() {
        assertFalse(RelayPool.isValidRelayUrl("https://relay.example"))
        assertFalse(RelayPool.isValidRelayUrl("wss://user@relay.example"))
        assertFalse(RelayPool.isValidRelayUrl("ws://"))
        assertFalse(RelayConnection.isLocalRelayUrl("wss://relay.example"))
    }
}
