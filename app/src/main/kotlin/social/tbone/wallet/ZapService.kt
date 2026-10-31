package social.tbone.wallet

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import social.tbone.account.AccountRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Dns
import java.net.InetAddress
import java.net.Proxy
import okhttp3.ResponseBody
import okio.Buffer
import social.tbone.Tunables
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.di.ImageClientProvider
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayPool
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the callback request required by NIP-57 Appendix B. In particular,
 * the bech32 `lnurl` tag must also be sent as the callback's `lnurl` query
 * parameter; some LNURL services reject requests that only include it inside
 * the signed zap event.
 */
internal fun buildNip57InvoiceCallbackUrl(
    callback: String,
    amountMsats: Long,
    zapRequest: Event,
): HttpUrl {
    require(amountMsats > 0) { "zap amount must be positive" }
    val base = callback.toHttpUrlOrNull() ?: error("invalid Lightning callback")
    require(base.scheme == "https") { "Lightning callback must use HTTPS" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.fragment == null) {
        "Lightning callback URL has unsupported credentials or fragment"
    }
    val lnurl = zapRequest.parsedTags
        .singleOrNull { it.name == "lnurl" }
        ?.value()
        ?: error("zap request must contain exactly one lnurl tag")

    val zapJson = Json.encodeToString(Event.serializer(), zapRequest)
    return base.newBuilder()
        // Do not let stale callback parameters shadow the exact values bound
        // into this zap request. Preserve unrelated service tokens/parameters.
        .removeAllQueryParameters("amount")
        .removeAllQueryParameters("comment")
        .removeAllQueryParameters("nostr")
        .removeAllQueryParameters("lnurl")
        .addQueryParameter("amount", amountMsats.toString())
        .addQueryParameter("comment", "")
        .addQueryParameter("nostr", zapJson)
        .addQueryParameter("lnurl", lnurl)
        .build()
}

/** The provider key signs receipts and is not the recipient's profile key. */
internal fun validateZapProvider(allowsNostr: Boolean, providerKey: String, minimum: Long, maximum: Long, amount: Long) {
    require(allowsNostr) { "This Lightning address does not support Nostr zaps" }
    require(providerKey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "Lightning address has an invalid Nostr pubkey" }
    require(amount in minimum..maximum) { "Zap amount is outside the recipient's ${minimum / 1000}-${maximum / 1000} sat range" }
}

/**
 * Creates a standards-compliant NIP-57 zap request and invoice, then returns
 * the verified BOLT-11 invoice for the UI to hand to an external wallet. T-Bone
 * never pays the invoice and never claims that opening a wallet completed it.
 */
@Singleton
class ZapService @Inject constructor(
    private val signerFactory: NostrSignerFactory,
    private val pool: RelayPool,
    private val accountRepository: AccountRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeZapMutex = Mutex()
    private val activeZapEventIds = ConcurrentHashMap.newKeySet<String>()

    /** New path: invoice from a NIP-A3 Lightning address (mirrors Monero flow). */
    suspend fun createInvoiceForAddress(
        event: Event,
        lightningAddress: String,
        amountSats: Long,
        expectedSender: String,
    ): Result<String> {
        val accepted = activeZapMutex.withLock { activeZapEventIds.add(event.id) }
        if (!accepted) {
            return Result.failure(IllegalStateException("An invoice for this note is already being prepared"))
        }
        return try {
            createInvoiceOnce(event, lightningAddress, amountSats, expectedSender)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                activeZapMutex.withLock { activeZapEventIds.remove(event.id) }
            }
        }
    }

    private suspend fun createInvoiceOnce(
        event: Event,
        lightningAddress: String,
        amountSats: Long,
        expectedSender: String,
    ): Result<String> {
        require(lightningAddress.isNotBlank()) { "Lightning address is empty" }
        require(event.tags.none { it.firstOrNull()?.toString()?.trim('"') == "zap" }) {
            "Zap-split notes are not supported yet; no invoice was created"
        }
        require(amountSats in 1..1_000_000) { "zap amount must be between 1 and 1,000,000 sats" }
        val amountMsats = Math.multiplyExact(amountSats, 1_000L)

        val payInfo = resolveLightningAddress(lightningAddress).getOrElse { return Result.failure(it) }
        validateZapProvider(payInfo.allowsNostr, payInfo.nostrPubkey, payInfo.minSendable, payInfo.maxSendable, amountMsats)

        require(accountRepository.activeAccount.first()?.pubkey == expectedSender) { "Active account changed; start the zap again" }
        val signer = signerFactory.forActiveAccount()
            ?: return Result.failure(IllegalStateException("No active Nostr signer"))
        require(signer.pubkey == expectedSender) { "Signer does not match the selected account" }
        val relayHints = pool.relayUrls()
            .take(5)
            .ifEmpty { Tunables.DEFAULT_RELAYS.take(3) }
        val lnurlTag = Nip19.lnurlToBech32(payInfo.endpointUrl)
            ?: return Result.failure(IllegalArgumentException("recipient Lightning address did not resolve to a valid HTTPS LNURL"))
        val zapTags = buildList {
            add(buildJsonArray {
                add("relays")
                relayHints.forEach { add(it) }
            })
            add(buildJsonArray { add("amount"); add(amountMsats.toString()) })
            // NIP-57 binds the bech32 LNURL-pay endpoint into the signed request.
            add(buildJsonArray { add("lnurl"); add(lnurlTag) })
            add(buildJsonArray { add("p"); add(event.pubkey) })
            add(buildJsonArray { add("e"); add(event.id) })
        }
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = 9734,
            content = "",
            tags = zapTags,
        )
        val zapRequest = signer.signEvent(unsigned).getOrElse { return Result.failure(it) }
        require(accountRepository.activeAccount.first()?.pubkey == expectedSender) { "Active account changed; start the zap again" }
        val sentTags = zapRequest.tags.filterNot { it.firstOrNull()?.jsonPrimitive?.contentOrNull == "client" }
        require(zapRequest.kind == 9734 && zapRequest.pubkey.equals(signer.pubkey, ignoreCase = true) &&
            zapRequest.createdAt == unsigned.createdAt && zapRequest.content == unsigned.content &&
            sentTags == unsigned.tags && zapRequest.verify()) {
            "The signed zap request could not be verified"
        }
        return requestInvoice(payInfo.callback, amountMsats, zapRequest)
    }

    private data class PayInfo(
        val endpointUrl: String,
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String,
    )

    /** Resolves either NIP-57 profile field: lud16 first, then lud06. */
    private suspend fun resolveLightningAddress(address: String): Result<PayInfo> = withContext(Dispatchers.IO) {
        try {
            val value = address.trim()
            require(value.isNotEmpty()) { "profile Lightning address is empty" }
            val url = if (value.contains('@')) {
                val at = value.lastIndexOf('@')
                require(at in 1 until value.lastIndex) { "invalid Lightning address" }
                val user = value.substring(0, at)
                val domain = value.substring(at + 1)
                require(!user.any(Char::isWhitespace) && !domain.any(Char::isWhitespace)) {
                    "invalid Lightning address"
                }
                val host = Uri.parse("https://$domain").host
                require(host.equals(domain, ignoreCase = true)) { "invalid Lightning address domain" }
                "https://$domain/.well-known/lnurlp/${Uri.encode(user)}"
            } else {
                Nip19.lnurlToUrl(value) ?: error("invalid LNURL payment address")
            }
            val metadataUrl = url.toHttpUrlOrNull() ?: error("invalid Lightning address URL")
            require(metadataUrl.scheme == "https" && metadataUrl.username.isEmpty() && metadataUrl.password.isEmpty()) {
                "Lightning address metadata must use HTTPS"
            }
            val response = fetchJson(metadataUrl, "Lightning address lookup")
            val obj = json.parseToJsonElement(response).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull?.equals("ERROR", ignoreCase = true) != true) {
                obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: "Lightning address lookup failed"
            }
            val callback = obj["callback"]?.jsonPrimitive?.contentOrNull
                ?: error("Lightning address has no callback")
            val callbackUrl = callback.toHttpUrlOrNull() ?: error("invalid Lightning callback")
            require(callbackUrl.scheme == "https" && callbackUrl.username.isEmpty() && callbackUrl.password.isEmpty()) {
                "Lightning callback must use HTTPS"
            }
            val minSendable = obj["minSendable"]?.jsonPrimitive?.longOrNull
                ?: error("Lightning address has no minimum")
            val maxSendable = obj["maxSendable"]?.jsonPrimitive?.longOrNull
                ?: error("Lightning address has no maximum")
            require(minSendable > 0 && maxSendable >= minSendable) { "Lightning address has invalid amount limits" }
            val pubkey = obj["nostrPubkey"]?.jsonPrimitive?.contentOrNull
                ?: error("Lightning address has no Nostr pubkey")
            require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                "Lightning address has an invalid Nostr pubkey"
            }
            Result.success(
                PayInfo(
                    endpointUrl = url,
                    callback = callback,
                    minSendable = minSendable,
                    maxSendable = maxSendable,
                    allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true ||
                        obj["allowsNostr"]?.jsonPrimitive?.booleanOrNull == true,
                    nostrPubkey = pubkey,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun requestInvoice(
        callback: String,
        amountMsats: Long,
        zapRequest: Event,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = buildNip57InvoiceCallbackUrl(
                callback = callback,
                amountMsats = amountMsats,
                zapRequest = zapRequest,
            )
            val body = fetchJson(url, "Invoice request")
            val obj = json.parseToJsonElement(body).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull?.equals("ERROR", ignoreCase = true) != true) {
                obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: "recipient rejected the zap"
            }
            val invoice = obj["pr"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("recipient returned no invoice")
            LightningInvoice.validateZap(invoice, amountMsats, Json.encodeToString(Event.serializer(), zapRequest))
            Result.success(invoice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Follow at most three same-host HTTPS redirects, checking every hop. */
    private fun fetchJson(initial: HttpUrl, stage: String): String {
        val client = httpClient()
        var url = initial
        repeat(4) { hop ->
            requireSafeLightningUrl(url)
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    require(hop < 3) { "$stage: too many redirects" }
                    val location = response.header("Location") ?: error("$stage: redirect has no destination")
                    val next = url.resolve(location) ?: error("$stage: invalid redirect")
                    requireSafeLightningUrl(next)
                    require(next.host == url.host && next.port == url.port) { "$stage: cross-host redirect blocked" }
                    url = next
                } else {
                    require(response.isSuccessful) { "$stage failed (HTTP ${response.code})" }
                    return response.body?.readUtf8Limited(MAX_JSON_BYTES) ?: error("$stage returned no response")
                }
            }
        }
        error("$stage: redirect limit exceeded")
    }

    /** Use the Tor-aware client; never perform local DNS for a proxy/Tor request. */
    private fun httpClient(): okhttp3.OkHttpClient {
        val base = ImageClientProvider.client
        return base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .apply {
                if (base.proxy == null || base.proxy?.type() == Proxy.Type.DIRECT) {
                    dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> =
                            Dns.SYSTEM.lookup(hostname).also { addresses ->
                                require(addresses.isNotEmpty() && addresses.all(::isPublicLightningAddress)) {
                                    "Lightning endpoint resolves to a private or local address"
                                }
                            }
                    })
                }
            }.build()
    }

    private fun ResponseBody.readUtf8Limited(maxBytes: Int): String {
        val buffer = Buffer()
        val source = source()
        var total = 0L
        while (true) {
            val remaining = maxBytes + 1L - total
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read == -1L) break
            total += read
            require(total <= maxBytes) { "HTTP response was too large" }
        }
        return buffer.readUtf8()
    }

    companion object {
        private const val MAX_JSON_BYTES = 128 * 1024
    }
}

/** Reject local/reserved endpoints, including mapped IPv4 and carrier NAT. */
internal fun isPublicLightningAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 4) {
        val a = bytes[0]; val b = bytes[1]
        if (a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 198 && b in 18..19)) return false
    } else if (bytes.size == 16) {
        if ((bytes[0] and 0xfe) == 0xfc) return false
    }
    return true
}

internal fun requireSafeLightningUrl(url: HttpUrl) {
    require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Lightning endpoint must use HTTPS without credentials" }
    val host = url.host.lowercase()
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Local Lightning endpoints are blocked" }
    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
        val ip = runCatching { InetAddress.getByName(host) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid Lightning endpoint")
        require(isPublicLightningAddress(ip)) { "Private Lightning endpoints are blocked" }
    }
}
