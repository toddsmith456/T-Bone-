package social.tbone.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.settings.ZapAmounts

class NwcProtocolTest {
    private val wallet = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val secret = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    @Test
    fun parsesEncodedAndUnencodedRelayForms() {
        val encoded = "nostr+walletconnect://$wallet?relay=wss%3A%2F%2Fwallet.example%2Fnwc&secret=$secret&lud16=alice%40example.com"
        val unencoded = "nostr+walletconnect://$wallet?relay=wss://wallet.example/nwc&secret=$secret"
        val normalized = "nwc://$wallet?relay=wss%3A%2F%2Fwallet.example%2Fnwc&secret=$secret"

        val firstResult = NwcProtocol.parse(encoded)
        val secondResult = NwcProtocol.parse(unencoded)
        val thirdResult = NwcProtocol.parse(normalized)
        assertTrue("encoded parse failed: ${firstResult.exceptionOrNull()}", firstResult.isSuccess)
        assertTrue("unencoded parse failed: ${secondResult.exceptionOrNull()}", secondResult.isSuccess)
        assertTrue("normalized parse failed: ${thirdResult.exceptionOrNull()}", thirdResult.isSuccess)
        val first = firstResult.getOrThrow()
        val second = secondResult.getOrThrow()
        val third = thirdResult.getOrThrow()

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
    fun parserFailuresDoNotEchoConnectionSecrets() {
        val result = NwcProtocol.parse("nostr+walletconnect://$wallet?relay=https%3A%2F%2Fwallet.example&secret=$secret")
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains(secret))
    }

    @Test
    fun rejectsMalformedWalletKey() {
        val value = "nostr+walletconnect://not-a-key?relay=wss%3A%2F%2Fwallet.example&secret=$secret"
        assertTrue(NwcProtocol.parse(value).isFailure)
    }

    @Test
    fun negotiatesCapabilitiesAndSupportsCurrentEncryptionNames() {
        val methods = NwcCapabilities.methodsFromInfoContent("get_info   pay_invoice\nget_balance notifications")
        assertTrue(NwcCapabilities.supportsMethod(methods, "PAY_INVOICE"))
        assertTrue(NwcCapabilities.supportsNip44(listOf("nip04", "NIP44_V2")))
        assertFalse(NwcCapabilities.supportsNip44(listOf("nip04")))
    }

    @Test
    fun reconnectPolicySurvivesTransientFailuresButHonorsDisconnect() {
        assertTrue(NwcReconnectPolicy.shouldAutoReconnect(true, false, NwcConnectionState.ERROR))
        assertTrue(NwcReconnectPolicy.shouldAutoReconnect(true, false, NwcConnectionState.CONNECTING))
        assertFalse(NwcReconnectPolicy.shouldAutoReconnect(true, false, NwcConnectionState.READY))
        assertFalse(NwcReconnectPolicy.shouldAutoReconnect(true, true, NwcConnectionState.ERROR))
        assertFalse(NwcReconnectPolicy.shouldAutoReconnect(true, false, NwcConnectionState.DISCONNECTED))
    }

    @Test
    fun zapControlsNeedEnabledAndValidatedReadyWallet() {
        assertFalse(canShowZapControls(false, NwcConnectionState.READY))
        assertFalse(canShowZapControls(true, NwcConnectionState.CONNECTING))
        assertFalse(canShowZapControls(true, NwcConnectionState.ERROR))
        assertTrue(canShowZapControls(true, NwcConnectionState.READY))
    }

    @Test
    fun unknownPaymentResultIsNeverAutomaticallyRetryable() {
        val unknown = NwcPaymentPolicy.classify(Result.failure<Unit>(NwcPaymentTimeoutException()))
        val failed = NwcPaymentPolicy.classify(Result.failure<Unit>(IllegalStateException("rejected")))
        assertEquals(NwcPaymentState.UNKNOWN, unknown)
        assertFalse(NwcPaymentPolicy.mayRetry(unknown))
        assertTrue(NwcPaymentPolicy.mayRetry(failed))
    }

    @Test
    fun normalizesEditableZapPresetsWithoutAcceptingUnsafeValues() {
        assertEquals(listOf(21L, 100L, 500L), ZapAmounts.parse(null))
        assertEquals(listOf(1L, 21L, 1_000_000L), ZapAmounts.normalize(listOf(1L, 21L, 21L, 0L, -5L, 1_000_000L)))
        assertFalse(ZapAmounts.normalize(listOf(0L, -1L)).contains(0L))
        assertTrue(runCatching { ZapAmounts.requireValid(1_000_001L) }.isFailure)
        assertEquals(listOf(100L, 500L, 21L), ZapAmounts.move(listOf(21L, 100L, 500L), 0, 2))
        assertEquals(listOf(21L, 100L, 500L), ZapAmounts.move(listOf(21L, 100L, 500L), -1, 2))
        assertEquals(listOf(21L), ZapAmounts.remove(listOf(21L), 21L))
        assertEquals(listOf(100L), ZapAmounts.remove(listOf(21L, 100L), 21L))
    }

    @Test
    fun profileLightningAddressPrefersLightningAddressAndSupportsLnurlFallback() {
        assertEquals(
            "alice@example.com",
            ProfileContent(lud16 = " alice@example.com ", lud06 = "LNURL1fallback").lightningAddress,
        )
        assertEquals("LNURL1fallback", ProfileContent(lud06 = " LNURL1fallback ").lightningAddress)
        assertEquals("https://pay.example/.well-known/lnurlp/alice", Nip19.lnurlToUrl("https://pay.example/.well-known/lnurlp/alice"))
        assertEquals(null, Nip19.lnurlToUrl("not-a-lightning-address"))
    }
}
