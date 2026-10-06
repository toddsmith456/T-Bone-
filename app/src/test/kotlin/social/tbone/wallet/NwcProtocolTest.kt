package social.tbone.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcProtocolTest {
    private val wallet = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val secret = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun parsesEncodedAndUnencodedRelayForms() {
        val encoded = "nostr+walletconnect://$wallet?relay=wss%3A%2F%2Fwallet.example%2Fnwc&secret=$secret&lud16=alice%40example.com"
        val unencoded = "nostr+walletconnect://$wallet?relay=wss://wallet.example/nwc&secret=$secret"
        val normalized = "nwc://$wallet?relay=wss%3A%2F%2Fwallet.example%2Fnwc&secret=$secret"

        val first = NwcProtocol.parse(encoded).getOrThrow()
        val second = NwcProtocol.parse(unencoded).getOrThrow()
        val third = NwcProtocol.parse(normalized).getOrThrow()

        assertEquals(listOf("wss://wallet.example/nwc"), first.relayUrls)
        assertEquals(first.relayUrls, second.relayUrls)
        assertEquals(first.relayUrls, third.relayUrls)
        assertEquals("alice@example.com", first.lud16)
        assertEquals(32, first.clientSecret.size)
    }

    @Test
    fun rejectsMissingSecretAndNonWalletRelay() {
        val missingSecret = "nostr+walletconnect://$wallet?relay=wss%3A%2F%2Fwallet.example"
        val badRelay = "nostr+walletconnect://$wallet?relay=https%3A%2F%2Fwallet.example&secret=$secret"

        assertTrue(NwcProtocol.parse(missingSecret).isFailure)
        assertTrue(NwcProtocol.parse(badRelay).isFailure)
    }

    @Test
    fun rejectsMalformedWalletKey() {
        val value = "nostr+walletconnect://not-a-key?relay=wss%3A%2F%2Fwallet.example&secret=$secret"
        assertTrue(NwcProtocol.parse(value).isFailure)
    }
}
