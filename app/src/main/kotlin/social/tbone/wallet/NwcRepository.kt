package social.tbone.wallet

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import social.tbone.account.signer.LocalKeySigner
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import social.tbone.settings.AppSettings
import social.tbone.settings.StoredNwcConnection
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** NWC connection lifecycle as seen by the wallet tool and zap buttons. */
enum class NwcConnectionState { NOT_CONFIGURED, CONNECTING, READY, ERROR, DISCONNECTED }

data class NwcWalletInfo(
    val alias: String? = null,
    val network: String? = null,
    val methods: List<String> = emptyList(),
)

data class NwcZapNotification(
    val id: String,
    val direction: String,
    val amountMsats: Long,
    val description: String,
    val createdAt: Long,
    val paymentHash: String? = null,
)

class NwcPaymentTimeoutException : Exception(
    "The wallet did not confirm this payment. Do not retry immediately; check the wallet first."
)

private data class NwcRpcResponse(
    val resultType: String?,
    val result: JsonObject?,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/**
 * A small, protocol-focused NIP-47 client. It deliberately uses the shared
 * RelayPool with a scoped subscription, so NWC requests are sent only to the
 * wallet relay and never leak onto T-Bone's social relays.
 */
@Singleton
class NwcRepository @Inject constructor(
    private val pool: RelayPool,
    private val appSettings: AppSettings,
    private val appScope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val lifecycleMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<NwcRpcResponse>>()

    private val _connectionState = MutableStateFlow(NwcConnectionState.NOT_CONFIGURED)
    val connectionState: StateFlow<NwcConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow("zaps are off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _balanceMsats = MutableStateFlow<Long?>(null)
    val balanceMsats: StateFlow<Long?> = _balanceMsats.asStateFlow()

    private val _walletInfo = MutableStateFlow<NwcWalletInfo?>(null)
    val walletInfo: StateFlow<NwcWalletInfo?> = _walletInfo.asStateFlow()

    private val _zapNotifications = MutableStateFlow<List<NwcZapNotification>>(emptyList())
    val zapNotifications: StateFlow<List<NwcZapNotification>> = _zapNotifications.asStateFlow()

    private var stored: StoredNwcConnection? = null
    private var signer: LocalKeySigner? = null
    private var relayUrls: Set<String> = emptySet()
    private var responseSubId: String? = null
    private var notificationSubId: String? = null
    private var messageJob: Job? = null
    private var infoWaiter: CompletableDeferred<Event>? = null
    private var encryption = NwcEncryption.NIP04

    private enum class NwcEncryption { NIP04, NIP44 }

    suspend fun connectSaved(): Result<Unit> = lifecycleMutex.withLock {
        val connection = appSettings.getNwcConnection()
        if (connection == null) {
            _connectionState.value = NwcConnectionState.NOT_CONFIGURED
            _status.value = "paste an NWC address to connect"
            return@withLock Result.failure(IllegalStateException("No NWC connection configured"))
        }
        connectLocked(connection)
    }

    /** Parses and encrypts the URI secret immediately; the raw URI is discarded. */
    suspend fun saveAndConnect(rawUri: String): Result<Unit> = lifecycleMutex.withLock {
        val parsed = NwcProtocol.parse(rawUri).getOrElse {
            _connectionState.value = NwcConnectionState.ERROR
            _status.value = it.message ?: "invalid NWC address"
            return@withLock Result.failure(it)
        }
        val (newSigner, encrypted) = try {
            LocalKeySigner.fromPrivateKey(parsed.clientSecret)
        } catch (e: Exception) {
            _connectionState.value = NwcConnectionState.ERROR
            _status.value = "could not protect the NWC secret on this device"
            return@withLock Result.failure(e)
        }
        val connection = StoredNwcConnection(
            walletPubkey = parsed.walletPubkey,
            relayUrls = parsed.relayUrls,
            clientPubkey = newSigner.pubkey,
            encryptedSecretBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            lud16 = parsed.lud16,
        )
        appSettings.setNwcConnection(connection)
        connectLocked(connection)
    }

    suspend fun clearSavedConnection() = lifecycleMutex.withLock {
        disconnectLocked(clearState = true)
        appSettings.clearNwcConnection()
        _connectionState.value = NwcConnectionState.NOT_CONFIGURED
        _status.value = "NWC connection removed"
    }

    suspend fun disconnect() = lifecycleMutex.withLock {
        disconnectLocked(clearState = false)
        _connectionState.value = if (stored == null) NwcConnectionState.NOT_CONFIGURED else NwcConnectionState.DISCONNECTED
        _status.value = "wallet disconnected"
    }

    fun configured(): Boolean = stored != null
    fun nwcRelayUrls(): Set<String> = relayUrls

    private suspend fun connectLocked(connection: StoredNwcConnection): Result<Unit> {
        disconnectLocked(clearState = false)
        stored = connection
        _connectionState.value = NwcConnectionState.CONNECTING
        _status.value = "connecting to wallet relay…"
        _balanceMsats.value = null
        _walletInfo.value = null

        val encrypted = try {
            Base64.decode(connection.encryptedSecretBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return connectionFailure("stored NWC secret is unreadable", e)
        }
        signer = LocalKeySigner(connection.clientPubkey, encrypted)
        relayUrls = connection.relayUrls.toSet()
        relayUrls.forEach { pool.acquireRelay(it) }

        messageJob = appScope.launch {
            pool.messages.collect { message -> route(message) }
        }

        responseSubId = "nwc-response-${UUID.randomUUID().toString().take(8)}"
        notificationSubId = "nwc-notification-${UUID.randomUUID().toString().take(8)}"
        pool.subscribeTo(
            filters = listOf(Filter(kinds = listOf(EventKind.NWC_RESPONSE), pTags = listOf(connection.clientPubkey))),
            id = responseSubId!!,
            label = "nwc-responses",
            relays = relayUrls,
        )
        pool.subscribeTo(
            filters = listOf(Filter(kinds = listOf(EventKind.NWC_NOTIFICATION), pTags = listOf(connection.clientPubkey))),
            id = notificationSubId!!,
            label = "nwc-zap-notifications",
            relays = relayUrls,
        )

        // NIP-47 says that an absent encryption tag means NIP-04. We only
        // choose NIP-44 after verifying the wallet's signed info event.
        val infoId = "nwc-info-${UUID.randomUUID().toString().take(8)}"
        val waiter = CompletableDeferred<Event>()
        infoWaiter = waiter
        pool.subscribeTo(
            filters = listOf(Filter(kinds = listOf(EventKind.NWC_INFO), authors = listOf(connection.walletPubkey), limit = 1)),
            id = infoId,
            label = "nwc-info",
            relays = relayUrls,
        )
        val info = withTimeoutOrNull(8_000) { waiter.await() }
        infoWaiter = null
        pool.unsubscribe(infoId)
        if (info == null) {
            return connectionFailure("wallet info event was not received")
        }

        encryption = if (info.supportsNip44()) NwcEncryption.NIP44 else NwcEncryption.NIP04
        _connectionState.value = NwcConnectionState.READY
        _status.value = if (encryption == NwcEncryption.NIP44) {
            "connected · NIP-44 encrypted"
        } else {
            "connected · legacy NIP-04 encryption"
        }
        val capability = refreshWalletInfo()
        val wallet = capability.getOrNull()
        if (wallet == null || "pay_invoice" !in wallet.methods) {
            return connectionFailure("wallet does not advertise pay_invoice capability")
        }
        return Result.success(Unit)
    }

    private suspend fun connectionFailure(message: String, error: Throwable? = null): Result<Unit> {
        if (error != null) Timber.w(error, "NWC connection failed: $message")
        else Timber.w("NWC connection failed: $message")
        disconnectLocked(clearState = false)
        _connectionState.value = NwcConnectionState.ERROR
        _status.value = message
        // Never leave payment controls enabled after validation/transport
        // failure. The user must explicitly reconnect and re-enable zaps.
        appSettings.setNwcZapsEnabled(false)
        return Result.failure(error ?: IllegalStateException(message))
    }

    private suspend fun disconnectLocked(clearState: Boolean) {
        responseSubId?.let(pool::unsubscribe)
        notificationSubId?.let(pool::unsubscribe)
        infoWaiter?.cancel()
        infoWaiter = null
        messageJob?.cancel()
        messageJob = null
        relayUrls.forEach { pool.releaseRelay(it) }
        relayUrls = emptySet()
        responseSubId = null
        notificationSubId = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        signer = null
        if (clearState) stored = null
    }

    private suspend fun route(message: PoolMessage) {
        val eventMessage = message.message as? RelayMessage.EventMessage ?: return
        val event = eventMessage.event
        val current = stored ?: return
        if (message.relayUrl !in relayUrls) return
        if (event.pubkey != current.walletPubkey || !event.verify()) return
        if (event.kind != EventKind.NWC_INFO &&
            event.parsedTags.none { it.name == "p" && it.value() == current.clientPubkey }
        ) return
        when (event.kind) {
            EventKind.NWC_INFO -> {
                if (eventMessage.subscriptionId.startsWith("nwc-info-")) infoWaiter?.complete(event)
            }
            EventKind.NWC_RESPONSE -> handleResponse(event)
            EventKind.NWC_NOTIFICATION -> handleNotification(event)
        }
    }

    private suspend fun handleResponse(event: Event) {
        val requestId = event.parsedTags
            .firstOrNull { it.name == "e" }
            ?.value()
            ?: return
        val waiter = pending.remove(requestId) ?: return
        val decrypted = decrypt(event.content)
        val response = decrypted.fold(
            onSuccess = { parseRpc(it) },
            onFailure = { NwcRpcResponse(null, null, "DECRYPT_FAILED", it.message ?: "could not decrypt wallet response") },
        )
        waiter.complete(response)
    }

    private suspend fun handleNotification(event: Event) {
        val decrypted = decrypt(event.content).getOrNull() ?: return
        val root = runCatching { json.parseToJsonElement(decrypted).jsonObject }.getOrNull() ?: return
        val type = root["notification_type"]?.jsonPrimitive?.contentOrNull ?: return
        if (type != "payment_received" && type != "payment_sent") return
        val payload = root["notification"]?.jsonObject ?: root
        // Wallet notifications can cover non-Nostr payments. Only retain a
        // notification as a zap when its metadata carries a Nostr zap request
        // (or the wallet explicitly labels its description as a zap).
        val metadata = payload["metadata"]?.toString().orEmpty()
        val description = payload["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (!metadata.contains("nostr", true) && !description.contains("zap", true)) return
        val amount = payload["amount"]?.jsonPrimitive?.longOrNull ?: return
        addZapNotification(
            NwcZapNotification(
                id = event.id,
                direction = if (type == "payment_received") "received" else "sent",
                amountMsats = amount,
                description = description.ifBlank { "zap" },
                createdAt = payload["created_at"]?.jsonPrimitive?.longOrNull ?: event.createdAt,
                paymentHash = payload["payment_hash"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }

    private suspend fun decrypt(content: String): Result<String> {
        val local = signer ?: return Result.failure(IllegalStateException("NWC signer unavailable"))
        return runCatching {
            if (encryption == NwcEncryption.NIP44) {
                local.nip44DecryptSync(content, stored!!.walletPubkey)
                    ?: throw IllegalArgumentException("NIP-44 decryption failed")
            } else {
                local.nip04DecryptSync(content, stored!!.walletPubkey)
            }
        }.recoverCatching {
            // Some older services omit the info event or mislabel the response.
            // A format fallback is safe because both decryptors authenticate the
            // payload; a random decode never becomes an accepted payment result.
            if (encryption == NwcEncryption.NIP44) local.nip04DecryptSync(content, stored!!.walletPubkey)
            else local.nip44DecryptSync(content, stored!!.walletPubkey)
                ?: throw IllegalArgumentException("NIP-44 decryption failed")
        }
    }

    private fun parseRpc(plaintext: String): NwcRpcResponse {
        val root = runCatching { json.parseToJsonElement(plaintext).jsonObject }
            .getOrElse { return NwcRpcResponse(null, null, "PARSE_ERROR", "invalid wallet response") }
        val error = root["error"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject
        if (error != null) {
            return NwcRpcResponse(
                resultType = root["result_type"]?.jsonPrimitive?.contentOrNull,
                result = null,
                errorCode = error["code"]?.jsonPrimitive?.contentOrNull ?: "OTHER",
                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "wallet error",
            )
        }
        return NwcRpcResponse(
            resultType = root["result_type"]?.jsonPrimitive?.contentOrNull,
            result = root["result"]?.jsonObject,
            errorCode = if (root["result"] == null) "PARSE_ERROR" else null,
            errorMessage = if (root["result"] == null) "wallet returned no result" else null,
        )
    }

    private suspend fun call(
        method: String,
        params: JsonObject,
        timeoutMs: Long = 15_000,
    ): Result<JsonObject> {
        val current = stored ?: return Result.failure(IllegalStateException("NWC is not configured"))
        val local = signer ?: return Result.failure(IllegalStateException("NWC signer unavailable"))
        if (_connectionState.value != NwcConnectionState.READY) {
            return Result.failure(IllegalStateException("NWC wallet is not ready"))
        }
        val plaintext = buildJsonObject {
            put("method", method)
            put("params", params)
        }.toString()
        val encrypted = if (encryption == NwcEncryption.NIP44) {
            local.nip44Encrypt(plaintext, current.walletPubkey).getOrElse { return Result.failure(it) }
        } else {
            local.nip04EncryptSync(plaintext, current.walletPubkey)
        }
        val tags = buildList {
            add(buildJsonArray { add("p"); add(current.walletPubkey) })
            if (encryption == NwcEncryption.NIP44) {
                add(buildJsonArray { add("encryption"); add("nip44_v2") })
            }
            add(buildJsonArray {
                add("expiration")
                add((System.currentTimeMillis() / 1000 + 180).toString())
            })
        }
        val unsigned = UnsignedEvent(
            pubkey = local.pubkey,
            kind = EventKind.NWC_REQUEST,
            content = encrypted,
            tags = tags,
        )
        val event = local.signEvent(unsigned).getOrElse { return Result.failure(it) }
        val waiter = CompletableDeferred<NwcRpcResponse>()
        pending[event.id] = waiter
        val sent = pool.publishTo(event, current.relayUrls.toSet())
        if (sent == 0) {
            pending.remove(event.id)
            return Result.failure(IllegalStateException("wallet relay is not connected"))
        }

        val response = try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: throw if (method == "pay_invoice") NwcPaymentTimeoutException()
                else java.util.concurrent.TimeoutException("wallet response timed out")
        } catch (e: TimeoutCancellationException) {
            throw e
        } catch (e: Exception) {
            pending.remove(event.id)
            return Result.failure(e)
        }
        if (response.errorCode != null) {
            return Result.failure(IllegalStateException("${response.errorCode}: ${response.errorMessage}"))
        }
        if (response.resultType != null && response.resultType != method) {
            return Result.failure(IllegalStateException("wallet response type did not match $method"))
        }
        return response.result?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("wallet returned an empty result"))
    }

    suspend fun refreshWalletInfo(): Result<NwcWalletInfo> {
        val result = call("get_info", buildJsonObject {})
        return result.map { body ->
            val info = NwcWalletInfo(
                alias = body["alias"]?.jsonPrimitive?.contentOrNull,
                network = body["network"]?.jsonPrimitive?.contentOrNull,
                methods = body["methods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            )
            _walletInfo.value = info
            info
        }
    }

    suspend fun refreshBalance(): Result<Long> {
        val result = call("get_balance", buildJsonObject {})
        return result.map { body ->
            val balance = body["balance"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalStateException("wallet returned no balance")
            _balanceMsats.value = balance
            balance
        }
    }

    /** Pays exactly one invoice. There is intentionally no automatic retry. */
    suspend fun payInvoice(invoice: String): Result<Unit> {
        if (!invoice.trim().lowercase().startsWith("ln")) {
            return Result.failure(IllegalArgumentException("wallet returned an invalid Lightning invoice"))
        }
        val body = call(
            method = "pay_invoice",
            params = buildJsonObject { put("invoice", invoice.trim()) },
            timeoutMs = 120_000,
        ).getOrElse { return Result.failure(it) }
        return try {
            val preimage = body["preimage"]?.jsonPrimitive?.contentOrNull
            if (preimage.isNullOrBlank()) throw NwcPaymentTimeoutException()
            refreshBalance()
            Result.success(Unit)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    fun recordOutgoingZap(eventId: String, amountMsats: Long, description: String, paymentHash: String? = null) {
        addZapNotification(
            NwcZapNotification(
                id = "outgoing-$eventId",
                direction = "sent",
                amountMsats = amountMsats,
                description = description.ifBlank { "zap" },
                createdAt = System.currentTimeMillis() / 1000,
                paymentHash = paymentHash,
            ),
        )
    }

    private fun addZapNotification(notification: NwcZapNotification) {
        _zapNotifications.value = (listOf(notification) + _zapNotifications.value)
            .distinctBy { it.id }
            .take(100)
    }

    private fun Event.supportsNip44(): Boolean =
        tags.any { tag ->
            tag.size >= 2 &&
                tag[0].jsonPrimitive.contentOrNull == "encryption" &&
                tag[1].jsonPrimitive.contentOrNull.orEmpty().split(' ').any { it == "nip44_v2" }
        }

    private fun Event.walletInfo(): NwcWalletInfo {
        val body = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
        return NwcWalletInfo(
            alias = body?.get("alias")?.jsonPrimitive?.contentOrNull,
            network = body?.get("network")?.jsonPrimitive?.contentOrNull,
        )
    }
}
