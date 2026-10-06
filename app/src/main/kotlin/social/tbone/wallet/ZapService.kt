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
        val lud16 = profile.lud16?.trim()
            ?: return Result.failure(IllegalArgumentException("This profile has no Lightning address"))
        require(amountSats in 1..1_000_000) { "zap amount must be between 1 and 1,000,000 sats" }
        val amountMsats = amountSats * 1_000L

        val payInfo = resolveLightningAddress(lud16).getOrElse { return Result.failure(it) }
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
        val zapTags = buildList {
            add(buildJsonArray {
                add("relays")
                relayHints.forEach { add(it) }
            })
            add(buildJsonArray { add("amount"); add(amountMsats.toString()) })
            add(buildJsonArray { add("lnurl"); add(lud16) })
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
        val payment = nwcRepository.payInvoice(invoice)
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
        val callback: String,
        val minSendable: Long,
        val maxSendable: Long,
        val allowsNostr: Boolean,
        val nostrPubkey: String,
    )

    private suspend fun resolveLightningAddress(lud16: String): Result<PayInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val at = lud16.lastIndexOf('@')
            require(at in 1 until lud16.lastIndex) { "invalid Lightning address" }
            val user = lud16.substring(0, at)
            val domain = lud16.substring(at + 1)
            require(Uri.parse("https://$domain").host == domain) { "invalid Lightning address domain" }
            val url = "https://$domain/.well-known/lnurlp/${Uri.encode(user)}"
            val response = ImageClientProvider.client.newCall(Request.Builder().url(url).get().build())
                .execute().use { r ->
                    require(r.isSuccessful) { "Lightning address lookup failed (${r.code})" }
                    r.body?.string() ?: error("Lightning address returned no metadata")
                }
            val obj = json.parseToJsonElement(response).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull != "ERROR") {
                obj["reason"]?.jsonPrimitive?.contentOrNull ?: "Lightning address lookup failed"
            }
            PayInfo(
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
            )
        }
    }

    private suspend fun requestInvoice(
        callback: String,
        amountMsats: Long,
        zapRequest: Event,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val base = callback.toHttpUrlOrNull() ?: error("invalid Lightning callback")
            require(base.scheme == "https" || base.scheme == "http") { "invalid Lightning callback scheme" }
            val url = base.newBuilder()
                .addQueryParameter("amount", amountMsats.toString())
                .addQueryParameter("nostr", Json.encodeToString(Event.serializer(), zapRequest))
                .build()
            val body = ImageClientProvider.client.newCall(Request.Builder().url(url).get().build())
                .execute().use { response ->
                    require(response.isSuccessful) { "invoice request failed (${response.code})" }
                    response.body?.string() ?: error("invoice response was empty")
                }
            val obj = json.parseToJsonElement(body).jsonObject
            require(obj["status"]?.jsonPrimitive?.contentOrNull != "ERROR") {
                obj["reason"]?.jsonPrimitive?.contentOrNull ?: "recipient rejected the zap"
            }
            val invoice = obj["pr"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: error("recipient returned no invoice")
            require(invoice.lowercase().startsWith("ln")) { "recipient returned an invalid invoice" }
            invoice
        }
    }
}
