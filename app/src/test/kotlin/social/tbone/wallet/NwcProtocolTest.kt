package social.tbone.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import social.tbone.nostr.Nip19
import social.tbone.nostr.Nip44
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.toHex
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
        val timeout = NwcPaymentPolicy.classify(Result.failure<Unit>(NwcPaymentTimeoutException()))
        val malformedResponse = NwcPaymentPolicy.classify(
            Result.failure<Unit>(NwcPaymentAmbiguousException("response could not be verified")),
        )
        val failed = NwcPaymentPolicy.classify(Result.failure<Unit>(IllegalStateException("rejected")))
        assertEquals(NwcPaymentState.UNKNOWN, timeout)
        assertEquals(NwcPaymentState.UNKNOWN, malformedResponse)
        assertFalse(NwcPaymentPolicy.mayRetry(timeout))
        assertFalse(NwcPaymentPolicy.mayRetry(malformedResponse))
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

    @Test
    fun nip44ConversationKeyMatchesAmethystVector() {
        val key = Nip44.conversationKey(
            "315e59ff51cb9209768cf7da80791ddcaae56ac9775eb25b6dee1234bc5d2268".hexToBytes(),
            "c2f9d9948dc8c7c38321e4b85c8558872eafa0641cd269db76848a6073e69133".hexToBytes(),
        )
        assertEquals("3dfef0ce2a4d80a25e7a328accf73448ef67096f65f79588e358d9a0eb9013f1", key.toHex())
    }

    @Test
    fun nip44MessageKeysMatchAmethystVector() {
        val keys = Nip44.messageKeys(
            "a1a3d60f3470a8612633924e91febf96dc5366ce130f658b1f0fc652c20b3b54".hexToBytes(),
            "e1e6f880560d6d149ed83dcc7e5861ee62a5ee051f7fde9975fe5d25d2a02d72".hexToBytes(),
        )
        assertEquals("f145f3bed47cb70dbeaac07f3a3fe683e822b3715edb7c4fe310829014ce7d76", keys.chachaKey.toHex())
        assertEquals("c4ad129bb01180c0933a160c", keys.chachaNonce.toHex())
        assertEquals("027c1db445f05e2eee864a0975b0ddef5b7110583c8c192de3732571ca5838c4", keys.hmacKey.toHex())
    }

    @Test
    fun nip44PayloadDecryptsWithAmethystVector() {
        val plaintext = Nip44.decryptWithConversationKey(
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb",
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes(),
        )
        assertEquals("a", plaintext)
    }

    @Test
    fun encodesLnurlTagAsBech32AndRoundTrips() {
        val endpoint = "https://pay.example/.well-known/lnurlp/alice"
        val encoded = Nip19.lnurlToBech32(endpoint)
        assertTrue(encoded?.startsWith("lnurl1") == true)
        assertEquals(endpoint, Nip19.lnurlToUrl(encoded!!))
    }

    @Test
    fun rejectsInvalidOrAmountlessBolt11BeforePayment() {
        assertEquals(null, LightningInvoice.amountMsats("not-an-invoice"))
        assertEquals(null, LightningInvoice.amountMsats(fakeInvoice("lnbc")))
        assertEquals(250_000_000L, LightningInvoice.amountMsats(fakeInvoice("lnbc2500u")))
        assertEquals(100_000L, LightningInvoice.amountMsats(fakeInvoice("lnbc1u")))
        assertEquals(null, LightningInvoice.amountMsats(fakeInvoice("lnbc1p")))
    }

    private fun fakeInvoice(hrp: String): String {
        val charset = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
        val data = listOf(0, 1, 2, 3, 4, 5)
        fun polymod(values: List<Int>): Int {
            val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b)
            var checksum = 1
            for (value in values) {
                val top = checksum ushr 25
                checksum = ((checksum and 0x1ffffff) shl 5) xor value
                for (i in generator.indices) if (((top ushr i) and 1) != 0) checksum = checksum xor generator[i]
            }
            return checksum
        }
        val expanded = hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }
        val checksum = polymod(expanded + data + List(6) { 0 }) xor 1
        val check = (0 until 6).map { (checksum ushr (5 * (5 - it))) and 31 }
        return hrp + "1" + (data + check).joinToString("") { charset[it].toString() }
    }
}
