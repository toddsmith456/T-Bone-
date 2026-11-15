package social.tbone.nostr.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayReconnectPolicyTest {

    @Test
    fun backsOffExponentiallyAndEntersCooldownAfterRapidFailures() {
        val policy = RelayReconnectPolicy(
            initialDelayMs = 5_000L,
            maxDelayMs = 60_000L,
            healthySessionMs = 10_000L,
            failureWindowMs = 60_000L,
            failureThreshold = 5,
            cooldownMs = 300_000L,
        )

        val d1 = policy.onDisconnect(nowMs = 1_000L, sessionDurationMs = 200L)
        assertEquals(5_000L, d1.delayMs)
        assertFalse(d1.inCooldown)

        val d2 = policy.onDisconnect(nowMs = 6_000L, sessionDurationMs = 100L)
        assertEquals(10_000L, d2.delayMs)
        assertFalse(d2.inCooldown)

        val d3 = policy.onDisconnect(nowMs = 16_000L, sessionDurationMs = 0L)
        assertEquals(20_000L, d3.delayMs)
        assertFalse(d3.inCooldown)

        val d4 = policy.onDisconnect(nowMs = 36_000L, sessionDurationMs = 500L)
        assertEquals(40_000L, d4.delayMs)
        assertFalse(d4.inCooldown)

        // 5th rapid failure within 60s trips the 5-minute cooldown circuit breaker.
        val d5 = policy.onDisconnect(nowMs = 50_000L, sessionDurationMs = 0L)
        assertEquals(300_000L, d5.delayMs)
        assertTrue(d5.inCooldown)

        // After cooldown completes, backoff starts cleanly at initialDelayMs again.
        val d6 = policy.onDisconnect(nowMs = 360_000L, sessionDurationMs = 0L)
        assertEquals(5_000L, d6.delayMs)
        assertFalse(d6.inCooldown)
    }

    @Test
    fun healthySessionResetsBackoffAndFailureHistory() {
        val policy = RelayReconnectPolicy(
            initialDelayMs = 5_000L,
            maxDelayMs = 60_000L,
            healthySessionMs = 10_000L,
            failureWindowMs = 60_000L,
            failureThreshold = 3,
            cooldownMs = 300_000L,
        )

        policy.onDisconnect(nowMs = 1_000L, sessionDurationMs = 100L)
        policy.onDisconnect(nowMs = 6_000L, sessionDurationMs = 100L)

        // Stayed connected for 15s -> healthy session resets failure counter & backoff.
        val afterHealthy = policy.onDisconnect(nowMs = 25_000L, sessionDurationMs = 15_000L)
        assertEquals(5_000L, afterHealthy.delayMs)
        assertFalse(afterHealthy.inCooldown)

        // Next short failure is failure #1, not #3 -> no cooldown yet.
        val nextShort = policy.onDisconnect(nowMs = 30_000L, sessionDurationMs = 50L)
        assertFalse(nextShort.inCooldown)
    }
}
