package social.tbone.wallet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19

class ZapServiceTest {
    @Test
    fun rejectsPrivateNetworkAddressesAndUnsafeUrls() {
        fun ip(text: String) = java.net.InetAddress.getByName(text)
        assertTrue(!isPublicLightningAddress(ip("127.0.0.1")))
        assertTrue(!isPublicLightningAddress(ip("10.1.2.3")))
        assertTrue(!isPublicLightningAddress(ip("100.64.0.1")))
        assertTrue(!isPublicLightningAddress(ip("::1")))
        assertTrue(!isPublicLightningAddress(ip("fc00::1")))
        assertTrue(isPublicLightningAddress(ip("8.8.8.8")))
        assertTrue(runCatching { requireSafeLightningUrl("http://example.org/".toHttpUrl()) }.isFailure)
        assertTrue(runCatching { requireSafeLightningUrl("https://127.0.0.1/".toHttpUrl()) }.isFailure)
    }

    @Test
    fun providerReceiptKeyIsNotRequiredToEqualRecipientKey() {
        validateZapProvider(true, "ab".repeat(32), 1000, 100_000, 21_000)
        assertTrue(runCatching { validateZapProvider(false, "ab".repeat(32), 1000, 100_000, 21_000) }.isFailure)
        assertTrue(runCatching { validateZapProvider(true, "not-a-key", 1000, 100_000, 21_000) }.isFailure)
        assertTrue(runCatching { validateZapProvider(true, "ab".repeat(32), 1000, 10_000, 21_000) }.isFailure)
    }

    @Test
    fun profilePrefersLightningAddressAndFallsBackToLnurl() {
        val p = social.tbone.nostr.ProfileContent(lud16 = "alice@example.org", lud06 = "lnurl1test")
        assertEquals("alice@example.org", p.lightningAddress)
        assertEquals("lnurl1test", p.copy(lud16 = " ").lightningAddress)
    }

    @Test
    fun callbackIncludesNip57LnurlParameterAndExactSignedZapRequest() {
        val endpoint = "https://pay.example/.well-known/lnurlp/alice"
        val lnurl = Nip19.lnurlToBech32(endpoint)!!
        val zapRequest = Event(
            id = "ab".repeat(32),
            pubkey = "cd".repeat(32),
            createdAt = 1_760_000_000,
            kind = 9734,
            tags = listOf(
                buildJsonArray { add("lnurl"); add(lnurl) },
                buildJsonArray { add("p"); add("ef".repeat(32)) },
            ),
            content = "",
            sig = "01".repeat(64),
        )

        val url = buildNip57InvoiceCallbackUrl(
            callback = "https://pay.example/callback?token=keep&amount=1&lnurl=stale",
            amountMsats = 21_000,
            zapRequest = zapRequest,
        )

        assertEquals("https", url.scheme)
        assertEquals("keep", url.queryParameter("token"))
        assertEquals("21000", url.queryParameter("amount"))
        assertEquals(listOf("21000"), url.queryParameterValues("amount"))
        assertEquals("", url.queryParameter("comment"))
        assertEquals(lnurl, url.queryParameter("lnurl"))
        assertEquals(listOf(lnurl), url.queryParameterValues("lnurl"))

        val serialized = url.queryParameter("nostr")
        assertNotNull(serialized)
        val eventJson = Json.parseToJsonElement(serialized!!).jsonObject
        assertEquals(zapRequest.id, eventJson["id"]?.jsonPrimitive?.content)
        assertEquals(lnurl, eventJson["tags"]?.jsonArray?.get(0)?.jsonArray?.get(1)?.jsonPrimitive?.content)
    }

    @Test
    fun callbackMustBeHttpsAndZapRequestMustHaveOneLnurlTag() {
        val noLnurl = Event(
            id = "ab".repeat(32),
            pubkey = "cd".repeat(32),
            createdAt = 1,
            kind = 9734,
            tags = emptyList(),
            content = "",
            sig = "01".repeat(64),
        )
        assertTrue(runCatching {
            buildNip57InvoiceCallbackUrl("http://pay.example/callback", 21_000, noLnurl)
        }.isFailure)
        assertTrue(runCatching {
            buildNip57InvoiceCallbackUrl("https://pay.example/callback", 21_000, noLnurl)
        }.isFailure)
    }
}
