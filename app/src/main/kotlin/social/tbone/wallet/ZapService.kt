package social.tbone.wallet

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import social.tbone.Tunables
import social.tbone.account.signer.NostrSignerFactory
import social.tbone.di.ImageClientProvider
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.RelayPool
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-57 zap flow backed by NWC. Every step is checked before the next one:
 * recipient address, LNURL metadata, amount bounds, recipient pubkey, signed
 * zap request, invoice, and finally the one-shot NWC payment. There is no
 * automatic payment retry because a timeout can mean the wallet is still
 * settling the invoice.
 */
@Singleton
class ZapService @Inject constructor(
    private val signerFactory: NostrSignerFactory,
    private val nwcRepository: NwcRepository,
    private val pool: RelayPool,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeZapMutex = Mutex()
    private val activeZapEventIds = ConcurrentHashMap.newKeySet<String>()

    /** The singleton service serializes each target event across all screens. */
    suspend fun zap(
        event: Event,
        profile: ProfileContent,
        amountSats: Long,
    ): Result<Unit> {
        val accepted = activeZapMutex.withLock { activeZapEventIds.add(event.id) }
        if (!accepted) return Result.failure(IllegalStateException("zap for this note is already in progress"))
        return try {
            zapOnce(event, profile, amountSats)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            activeZapMutex.withLock { activeZapEventIds.remove(event.id) }
        }
    }

    private suspend fun zapOnce(
        event: Event,
        profile: ProfileContent,
        amountSats: Long,
    ): Result<Unit> {
        val lightningAddress = profile.lightningAddress
            ?: return Result.failure(IllegalArgumentException("This profile has no Lightning address"))
        require(amountSats in 1..1_000_000) { "zap amount must be between 1 and 1,000,000 sats" }
        val amountMsats = Math.multiplyExact(amountSats, 1_000L)

        val payInfo = resolveLightningAddress(lightningAddress).getOrElse { return Result.failure(it) }
        require(payInfo.allowsNostr) { "This Lightning address does not support Nostr zaps" }
        require(payInfo.nostrPubkey.equals(event.pubkey, ignoreCase = true)) {
            "Lightning address belongs to a different Nostr pubkey"
        }
        require(amountMsats in payInfo.minSendable..payInfo.maxSendable) {
            "Zap amount is outside the recipient's ${payInfo.minSendable / 1000}-${payInfo.maxSendable / 1000} sat range"
        }

        val signer = signerFactory.forActiveAccount()
            ?: return Result.failure(IllegalStateException("No active Nostr signer"))
        val relayHints = pool.relayUrls()
            .filterNot { it in nwcRepository.nwcRelayUrls() }
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
            // NIP-57 requires the LNURL-pay endpoint in bech32 form here. A
            // lud16 such as alice@example.com is not a valid replacement: the
            // provider and receipt validator use this tag to bind the payment.
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
        val invoice = requestInvoice(payInfo.callback, amountMsats, zapRequest)
            .getOrElse { return Result.failure(it) }

        // Important: call pay exactly once. If this returns a timeout, the UI
        // tells the user to check the wallet rather than creating a duplicate.
        val payment = nwcRepository.payInvoice(
            invoice = invoice,
            zapRequest = zapRequest,
            recipientIdentifier = lightningAddress,
            targetEventId = event.id,
        )
        if (payment.isSuccess) {
            nwcRepository.recordOutgoingZap(
                eventId = event.id,
                amountMsats = amountMsats,
                description = "zap · ${profile.bestName ?: event.pubkey.take(8)}",
            )
        }
        return payment
    }

    private data class PayInfo(
        val endpointUrl: String,
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String,
    )

    /**
     * Resolves either NIP-57 profile field: lud16 is a Lightning Address and
     * lud06 is a bech32 LNURL-pay endpoint. This mirrors Amethyst's
     * `lnAddress()` preference while keeping the endpoint validation here at
     * the payment boundary.
     */
    private suspend fun resolveLightningAddress(address: String): Result<PayInfo> = withContext(Dispatchers.IO) {
        try {
            val value = address.trim()
            require(value.isNotEmpty()) { "profile Lightning address is empty" }
            val url = if (value.contains('@')) {
                val at = value.lastIndexOf('@')
                require(at in 1 until value.lastIndex) { "invalid Lightning address" }
                val user = value.substring(0, at)
                val domain = value.substring(at + 1)
                require(!domain.any(Char::isWhitespace)) { "invalid Lightning address domain" }
                val host = Uri.parse("https://$domain").host
                require(host.equals(domain, ignoreCase = true)) { "invalid Lightning address domain" }
                "https://$domain/.well-known/lnurlp/${Uri.encode(user)}"
            } else {
                Nip19.lnurlToUrl(value) ?: error("invalid LNURL payment address")
            }
            val callbackUrl = url.toHttpUrlOrNull() ?: error("invalid Lightning callback")
            require(callbackUrl.scheme == "https") {
                "Lightning callback must use HTTPS"
            }
            val response = ImageClientProvider.client.newCall(Request.Builder().url(callbackUrl).get().build())
                .execute().use { r ->
                    require(r.isSuccessful) { "Lightning address lookup failed (${r.code})" }
                    r.body?.string() ?: error("Lightning address returned no metadata")
                }
            val obj = json.parseToJsonElement(response).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull?.equals("ERROR", ignoreCase = true) != true) {
                obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: "Lightning address lookup failed"
            }
            Result.success(
                PayInfo(
                    endpointUrl = url,
                    callback = obj["callback"]?.jsonPrimitive?.contentOrNull
                        ?: error("Lightning address has no callback"),
                    minSendable = obj["minSendable"]?.jsonPrimitive?.longOrNull
                        ?: error("Lightning address has no minimum"),
                    maxSendable = obj["maxSendable"]?.jsonPrimitive?.longOrNull
                        ?: error("Lightning address has no maximum"),
                    allowsNostr = obj["allowsNostr"]?.jsonPrimitive?.contentOrNull?.toBoolean() == true ||
                        obj["allowsNostr"]?.jsonPrimitive?.booleanOrNull == true,
                    nostrPubkey = obj["nostrPubkey"]?.jsonPrimitive?.contentOrNull
                        ?: error("Lightning address has no Nostr pubkey"),
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
            val base = callback.toHttpUrlOrNull() ?: error("invalid Lightning callback")
            require(base.scheme == "https") { "Lightning callback must use HTTPS" }
            val url = base.newBuilder()
                .addQueryParameter("amount", amountMsats.toString())
                // Amethyst sends comment= even for an empty message; some
                // LNURL servers require the parameter to be present.
                .addQueryParameter("comment", "")
                .addQueryParameter("nostr", Json.encodeToString(Event.serializer(), zapRequest))
                .build()
            val body = ImageClientProvider.client.newCall(Request.Builder().url(url).get().build())
                .execute().use { response ->
                    require(response.isSuccessful) { "invoice request failed (${response.code})" }
                    response.body?.string() ?: error("invoice response was empty")
                }
            val obj = json.parseToJsonElement(body).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull?.equals("ERROR", ignoreCase = true) != true) {
                obj["reason"]?.jsonPrimitive?.contentOrNull
                    ?: obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: "recipient rejected the zap"
            }
            val invoice = obj["pr"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("recipient returned no invoice")
            val invoiceAmount = LightningInvoice.amountMsats(invoice)
                ?: error("recipient returned an invalid or amountless invoice")
            require(invoiceAmount == amountMsats) {
                "invoice amount mismatch: got $invoiceAmount msats, expected $amountMsats msats"
            }
            Result.success(invoice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
