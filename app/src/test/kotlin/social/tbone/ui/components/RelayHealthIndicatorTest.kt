package social.tbone.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayHealthIndicatorTest {
    @Test
    fun noConfiguredOrConnectedRelaysIsRedOffline() {
        assertEquals(RelayHealth.OFFLINE, relayHealth(0, 0))
        assertEquals(RelayHealth.OFFLINE, relayHealth(0, 6))
        assertEquals(RelayHealth.OFFLINE, relayHealth(-1, 6))
    }

    @Test
    fun belowOneThirdConnectedIsOrange() {
        assertEquals(RelayHealth.LOW, relayHealth(1, 6))
        assertEquals(RelayHealth.LOW, relayHealth(1, 4))
        assertEquals(RelayHealth.LOW, relayHealth(1, 5))
    }

    @Test
    fun oneThirdThroughThreeQuartersConnectedIsYellow() {
        assertEquals(RelayHealth.MEDIUM, relayHealth(1, 3))
        assertEquals(RelayHealth.MEDIUM, relayHealth(1, 2))
        assertEquals(RelayHealth.MEDIUM, relayHealth(2, 3))
        assertEquals(RelayHealth.MEDIUM, relayHealth(3, 4))
    }

    @Test
    fun moreThanThreeQuartersIsGreenAndCountIsClamped() {
        assertEquals(RelayHealth.HIGH, relayHealth(4, 5))
        assertEquals(RelayHealth.HIGH, relayHealth(8, 6))
    }
}
