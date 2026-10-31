package social.tbone.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import social.tbone.account.Account
import social.tbone.account.SignerType
import social.tbone.Tunables

class RelayOwnershipTest {
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val client = OkHttpClient()
    private fun pool() = RelayPool(scope) { RelayConnection(it, client) }
    private val a = "wss://one.example"
    private val b = "wss://two.example"

    @After fun close() = scope.cancel()

    @Test fun `temporary lease cannot remove a configured relay`() {
        val pool = pool()
        pool.setPersonalRelays(listOf(a))
        pool.acquireRelay(a)
        pool.releaseRelay(a)
        pool.removeRelay(a)
        assertEquals(setOf(a), pool.relayUrls())
    }

    @Test fun `overlapping leases survive until last owner leaves`() {
        val pool = pool()
        pool.acquireRelay(a)
        pool.acquireRelay(a)
        pool.releaseRelay(a)
        assertEquals(setOf(a), pool.relayUrls())
        pool.releaseRelay(a)
        assertEquals(emptySet<String>(), pool.relayUrls())
        pool.releaseRelay(a)
        assertEquals(emptySet<String>(), pool.relayUrls())
    }

    @Test fun `switching accounts removes only unowned old relays`() {
        val pool = pool()
        pool.setPersonalRelays(listOf(a))
        pool.acquireRelay(a)
        pool.setPersonalRelays(listOf(b))
        assertEquals(setOf(a, b), pool.relayUrls())
        pool.releaseRelay(a)
        assertEquals(setOf(b), pool.relayUrls())
    }

    @Test fun `global and personal pools have no shared connection state`() {
        val personal = pool()
        val global = pool()
        personal.setPersonalRelays(listOf(a))
        global.addRelay(a)
        global.addRelay(b)
        global.relayUrls().forEach(global::removeRelay)
        assertEquals(setOf(a), personal.relayUrls())
        assertEquals(emptySet<String>(), global.relayUrls())
    }

    @Test fun `transport reset preserves both personal and temporary ownership`() {
        val pool = pool()
        pool.setPersonalRelays(listOf(a))
        pool.acquireRelay(b)
        pool.updateTransportFactory { client }
        assertEquals(setOf(a, b), pool.relayUrls())
        pool.releaseRelay(b)
        assertEquals(setOf(a), pool.relayUrls())
    }

    @Test fun `global session connects only while active and removes disabled relays`() {
        val global = pool()
        val session = GlobalRelaySession(global)
        session.reconcile(false, setOf(a, b))
        assertEquals(emptySet<String>(), global.relayUrls())
        session.reconcile(true, setOf(a, b))
        assertEquals(setOf(a, b), global.relayUrls())
        session.reconcile(true, setOf(b))
        assertEquals(setOf(b), global.relayUrls())
        session.reconcile(false, setOf(b))
        assertEquals(emptySet<String>(), global.relayUrls())
    }

    @Test fun `legacy empty account uses defaults but intentional empty stays empty`() {
        val account = Account(pubkey = "abc", signerType = SignerType.AMBER)
        assertEquals(Tunables.DEFAULT_RELAYS, account.effectiveRelays())
        assertEquals(emptyList<String>(), account.copy(relayListConfigured = true).effectiveRelays())
        assertEquals(listOf(a), account.copy(relays = listOf(a)).effectiveRelays())
    }
}
