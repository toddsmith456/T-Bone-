package social.tbone.wallet

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** NWC connection lifecycle as seen by the wallet tool and zap buttons. */
enum class NwcConnectionState { NOT_CONFIGURED, CONNECTING, READY, ERROR, DISCONNECTED }

data class NwcWalletInfo(
    val alias: String? = null,
    val network: String? = null,
    val methods: List<String> = emptyList(),
    val extensions: Set<String> = emptySet(),
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

/** The wallet response was malformed or could not be authenticated after dispatch. */
class NwcPaymentAmbiguousException(message: String) : Exception(message)

/** A payment that timed out in the UI and was later settled or rejected by the wallet. */
data class NwcLatePayment(val targetEventId: String, val state: NwcPaymentState)

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
    /** NWC notifications are relay-backed but intentionally session-only. */
    private val seenNotificationIds = ConcurrentHashMap.newKeySet<String>()
    private var notificationSince = 0L

    private val _connectionState = MutableStateFlow(NwcConnectionState.NOT_CONFIGURED)
    val connectionState: StateFlow<NwcConnectionState> = _connectionState.asStateFlow()

    private val _status = MutableStateFlow("zaps are off")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _balanceMsats = MutableStateFlow<Long?>(null)
    val balanceMsats: StateFlow<Long?> = _balanceMsats.asStateFlow()

    private val _walletInfo = MutableStateFlow<NwcWalletInfo?>(null)
    val walletInfo: StateFlow<NwcWalletInfo?> = _walletInfo.asStateFlow()

    /** Late answers to payments whose UI wait already ended as "unknown". */
    private val _latePayments = MutableSharedFlow<NwcLatePayment>(extraBufferCapacity = 16)
    val latePayments: SharedFlow<NwcLatePayment> = _latePayments.asSharedFlow()

    private val _zapNotifications = MutableStateFlow<List<NwcZapNotification>>(emptyList())
    val zapNotifications: StateFlow<List<NwcZapNotification>> = _zapNotifications.asStateFlow()

    private var stored: StoredNwcConnection? = null
    private var signer: LocalKeySigner? = null
    private var relayUrls: Set<String> = emptySet()
    private var responseSubId: String? = null
    private var notificationSubId: String? = null
    private var messageJob: Job? = null
    /** Retries initial/recovery handshakes; cancelled only by an explicit Disconnect. */
    private var reconnectJob: Job? = null
    /** The current handshake job, so Disconnect can cancel a slow relay wait immediately. */
    private var handshakeJob: Job? = null
    private var infoWaiter: CompletableDeferred<Event>? = null
    private var encryption = NwcEncryption.NIP04
    /** NWC-06 metadata is sent only after the wallet advertises extension 06. */
    private var supportsNwcMetadata = false

    private enum class NwcEncryption { NIP04, NIP44 }

    suspend fun connectSaved(): Result<Unit> {
        val result = lifecycleMutex.withLock {
            val connection = appSettings.getNwcConnection()
            if (connection == null) {
                _connectionState.value = NwcConnectionState.NOT_CONFIGURED
                _status.value = "paste an NWC address to connect"
                return@withLock Result.failure(IllegalStateException("No NWC connection configured"))
            }
            if (appSettings.isNwcUserDisconnected()) {
                // Keep the saved secret available for an explicit Reconnect, but
                // never turn a user's Disconnect into an automatic reconnect.
                stored = connection
                _connectionState.value = NwcConnectionState.DISCONNECTED
                _status.value = "wallet disconnected · tap reconnect to resume"
                return@withLock Result.failure(IllegalStateException("Wallet was disconnected by the user"))
            }
            if (storedMatches(connection) &&
                (_connectionState.value == NwcConnectionState.READY || _connectionState.value == NwcConnectionState.CONNECTING)
            ) {
                return@withLock Result.success(Unit)
            }
            connectLocked(connection)
        }
        val savedConnection = appSettings.getNwcConnection()
        val explicitlyDisconnected = appSettings.isNwcUserDisconnected()
        if (result.isFailure && NwcReconnectPolicy.shouldAutoReconnect(savedConnection != null, explicitlyDisconnected, _connectionState.value)) {
            scheduleReconnect()
        }
        return result
    }

    /** Reconnect is an explicit user action after Disconnect. */
    suspend fun reconnectSaved(): Result<Unit> {
        appSettings.setNwcUserDisconnected(false)
        return connectSaved()
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
        reconnectJob?.cancel()
        reconnectJob = null
        appSettings.setNwcUserDisconnected(false)
        appSettings.setNwcConnection(connection)
        val result = connectLocked(connection)
        if (result.isFailure) scheduleReconnect()
        result
    }

    suspend fun clearSavedConnection() {
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        lifecycleMutex.withLock {
            reconnectJob = null
            handshakeJob = null
            disconnectLocked(clearState = true)
            appSettings.clearNwcConnection()
            appSettings.setNwcUserDisconnected(false)
            _connectionState.value = NwcConnectionState.NOT_CONFIGURED
            _status.value = "NWC connection removed"
        }
    }

    suspend fun disconnect() {
        // Do this before taking the lifecycle mutex. A handshake can be waiting
        // on an 8-second info query or a 15-second get_info response; explicit
        // Disconnect must not wait for either timeout.
        reconnectJob?.cancel()
        handshakeJob?.cancel()
        lifecycleMutex.withLock {
            reconnectJob = null
            handshakeJob = null
            disconnectLocked(clearState = false)
            appSettings.setNwcUserDisconnected(true)
            appSettings.setNwcZapsEnabled(false)
            _connectionState.value = if (stored == null) NwcConnectionState.NOT_CONFIGURED else NwcConnectionState.DISCONNECTED
            _status.value = "wallet disconnected"
        }
    }

    fun configured(): Boolean = stored != null
    fun nwcRelayUrls(): Set<String> = relayUrls

    private fun storedMatches(connection: StoredNwcConnection): Boolean =
        stored?.walletPubkey == connection.walletPubkey &&
            stored?.clientPubkey == connection.clientPubkey &&
            stored?.relayUrls?.toSet() == connection.relayUrls.toSet()

    /**
     * An info event can be missed while a relay is reconnecting. Keep trying the
     * handshake in the application scope; RelayPool itself replays subscriptions
     * after every WebSocket reconnect. This job stops only when the user chooses
     * Disconnect or removes the saved wallet.
     */
    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = appScope.launch {
            var delayMs = 2_000L
            while (true) {
                kotlinx.coroutines.delay(delayMs)
                val shouldStop = lifecycleMutex.withLock {
                    val connection = appSettings.getNwcConnection()
                    if (connection == null || appSettings.isNwcUserDisconnected()) {
                        true
                    } else if (_connectionState.value == NwcConnectionState.READY && storedMatches(connection)) {
                        true
                    } else {
                        connectLocked(connection).isSuccess
                    }
                }
                if (shouldStop) break
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private suspend fun connectLocked(connection: StoredNwcConnection): Result<Unit> {
        val job = coroutineContext[Job]
        handshakeJob = job
        return try {
            connectLockedBody(connection)
        } finally {
            if (handshakeJob === job) handshakeJob = null
        }
    }

    private suspend fun connectLockedBody(connection: StoredNwcConnection): Result<Unit> {
        disconnectLocked(clearState = false)
        stored = connection
        _connectionState.value = NwcConnectionState.CONNECTING
        _status.value = "connecting to wallet relay…"
        _balanceMsats.value = null
        _walletInfo.value = null
        supportsNwcMetadata = false

        val encrypted = try {
            Base64.decode(connection.encryptedSecretBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return connectionFailure("stored NWC secret is unreadable", e)
        }
        signer = LocalKeySigner(connection.clientPubkey, encrypted)
        relayUrls = connection.relayUrls.toSet()
        relayUrls.forEach { pool.acquireRelay(it) }
        // Match Amethyst's account-scoped NWC watcher: ask the wallet relay
        // for live notifications from the moment this connection is mounted,
        // not for a historical backlog. The list remains memory-only.
        notificationSince = System.currentTimeMillis() / 1000
        seenNotificationIds.clear()

        // Start the multiplexer before sending any REQ/EVENT frames. An
        // undispatched launch reaches collect immediately, avoiding the small
        // startup window where a fast wallet response could be emitted before
        // this repository is listening.
        messageJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
            filters = listOf(
                Filter(
                    kinds = listOf(EventKind.NWC_NOTIFICATION, EventKind.NWC_NOTIFICATION_V2),
                    authors = listOf(connection.walletPubkey),
                    pTags = listOf(connection.clientPubkey),
                    since = notificationSince,
                ),
            ),
            id = notificationSubId!!,
            label = "nwc-zap-notifications",
            relays = relayUrls,
        )

        // NIP-47 recommends an info event, but a number of otherwise proper
        // services (including mobile wallets) answer requests without publishing
        // one. Use it when present, then fall back to NIP-04 and negotiate again
        // with the safe get_info call if necessary. Never guess NIP-44 for a
        // payment request without an advertisement.
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

        encryption = if (info?.supportsNip44() == true) NwcEncryption.NIP44 else NwcEncryption.NIP04
        val advertisedMethods = info?.let { NwcCapabilities.methodsFromInfoContent(it.content) }.orEmpty()
        val advertisedExtensions = info?.advertisedExtensions().orEmpty()
        supportsNwcMetadata = NwcCapabilities.METADATA_EXTENSION in advertisedExtensions
        if (advertisedMethods.isNotEmpty() && !NwcCapabilities.supportsMethod(advertisedMethods, NwcCapabilities.PAY_INVOICE)) {
            return connectionFailure("wallet does not advertise pay_invoice capability")
        }
        if (advertisedMethods.isNotEmpty() || advertisedExtensions.isNotEmpty()) {
            _walletInfo.value = NwcWalletInfo(
                methods = advertisedMethods,
                extensions = advertisedExtensions,
            )
        }

        // Keep the public state CONNECTING until pay_invoice has been verified;
        // otherwise the UI could enable a zap button during a slow get_info call.
        _status.value = if (encryption == NwcEncryption.NIP44) {
            "negotiating wallet capabilities · NIP-44"
        } else if (info == null) {
            "negotiating wallet capabilities · NIP-04"
        } else {
            "negotiating wallet capabilities"
        }

        // get_info is the authoritative capability check when available. If a
        // wallet omitted its replaceable info event, retry this read once with
        // NIP-44; that retry cannot pay or change wallet state.
        var capability = refreshWalletInfo()
        if (capability.isFailure && info == null) {
            encryption = NwcEncryption.NIP44
            val nip44Capability = refreshWalletInfo()
            if (nip44Capability.isSuccess) capability = nip44Capability
            else encryption = NwcEncryption.NIP04
        }
        val wallet = capability.getOrNull()
        if (wallet == null && !NwcCapabilities.supportsMethod(advertisedMethods, NwcCapabilities.PAY_INVOICE)) {
            return connectionFailure("wallet capabilities could not be verified")
        }
        if (wallet != null && !NwcCapabilities.supportsMethod(wallet.methods, NwcCapabilities.PAY_INVOICE)) {
            return connectionFailure("wallet does not advertise pay_invoice capability")
        }
        _connectionState.value = NwcConnectionState.READY
        _status.value = when {
            encryption == NwcEncryption.NIP44 -> "connected · NIP-44 encrypted"
            info == null -> "connected · NIP-04 fallback"
            else -> "connected · legacy NIP-04 encryption"
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
        seenNotificationIds.clear()
        notificationSince = 0L
        _zapNotifications.value = emptyList()
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
            EventKind.NWC_NOTIFICATION, EventKind.NWC_NOTIFICATION_V2 -> {
                // A wallet relay can return the same event through multiple
                // connections; Amethyst de-duplicates by event id before
                // decrypting and fan-out.
                if (seenNotificationIds.add(event.id)) handleNotification(event)
            }
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
        // Follow Amethyst's NwcSignerState: a payment is a zap only when the
        // NIP-47 transaction metadata contains the structured `nostr` object.
        // Do not guess from a memo such as "zap"; that would leak unrelated
        // wallet payments into this zap-only view.
        val metadata = payload["metadata"]?.let { element ->
            runCatching { element.jsonObject }.getOrNull()
        }
        if (metadata?.containsKey("nostr") != true) return
        val description = payload["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val amount = payload["amount"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it > 0 }
            ?: return
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
        return try {
            Result.success(
                if (encryption == NwcEncryption.NIP44) {
                    local.nip44DecryptSync(content, stored!!.walletPubkey)
                        ?: throw IllegalArgumentException("NIP-44 decryption failed")
                } else {
                    local.nip04DecryptSync(content, stored!!.walletPubkey)
                },
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            try {
                // Some older services omit the info event or mislabel the response.
                // A format fallback is safe because both decryptors authenticate the
                // payload; a random decode never becomes an accepted payment result.
                Result.success(
                    if (encryption == NwcEncryption.NIP44) local.nip04DecryptSync(content, stored!!.walletPubkey)
                    else local.nip44DecryptSync(content, stored!!.walletPubkey)
                        ?: throw IllegalArgumentException("NIP-44 decryption failed"),
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
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
        lateTargetEventId: String? = null,
    ): Result<JsonObject> {
        val current = stored ?: return Result.failure(IllegalStateException("NWC is not configured"))
        val local = signer ?: return Result.failure(IllegalStateException("NWC signer unavailable"))
        if (_connectionState.value != NwcConnectionState.READY &&
            !(method == "get_info" && _connectionState.value == NwcConnectionState.CONNECTING)
        ) {
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
            // Payments carry no expiration. A slow or briefly offline wallet must
            // still be able to answer; an expired request would leave the payment
            // permanently unresolved.
            if (method != "pay_invoice") {
                add(buildJsonArray {
                    add("expiration")
                    add((System.currentTimeMillis() / 1000 + 180).toString())
                })
            }
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

        val awaited = try {
            withTimeoutOrNull(timeoutMs) { waiter.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            pending.remove(event.id)
            throw e
        } catch (e: Exception) {
            pending.remove(event.id)
            return Result.failure(e)
        }
        val response = awaited ?: run {
            if (method == "pay_invoice") {
                // Keep the waiter registered: the wallet may still answer. The
                // late answer is reconciled instead of the note staying blocked.
                trackLatePayment(waiter, lateTargetEventId)
                return Result.failure(NwcPaymentTimeoutException())
            }
            pending.remove(event.id)
            return Result.failure(java.util.concurrent.TimeoutException("wallet response timed out"))
        }
        return interpretResponse(method, response)
    }

    private fun interpretResponse(method: String, response: NwcRpcResponse): Result<JsonObject> {
        if (response.errorCode != null) {
            if (method == "pay_invoice" && response.errorCode in setOf("DECRYPT_FAILED", "PARSE_ERROR")) {
                return Result.failure(
                    NwcPaymentAmbiguousException(
                        "The wallet response could not be verified. Check the wallet before retrying.",
                    ),
                )
            }
            return Result.failure(IllegalStateException("${response.errorCode}: ${response.errorMessage}"))
        }
        if (response.resultType != null && response.resultType != method) {
            return if (method == "pay_invoice") {
                Result.failure(NwcPaymentAmbiguousException("The wallet returned an unexpected payment response. Check the wallet before retrying."))
            } else {
                Result.failure(IllegalStateException("wallet response type did not match $method"))
            }
        }
        return response.result?.let { Result.success(it) }
            ?: if (method == "pay_invoice") {
                Result.failure(NwcPaymentAmbiguousException("The wallet returned no payment result. Check the wallet before retrying."))
            } else {
                Result.failure(IllegalStateException("wallet returned an empty result"))
            }
    }

    /**
     * A pay_invoice whose wait ran out may still be settled by the wallet. Keep
     * observing the same request; a late answer is published on [latePayments]
     * so the UI can clear the "unknown" block and show the real outcome.
     */
    private fun trackLatePayment(
        waiter: CompletableDeferred<NwcRpcResponse>,
        targetEventId: String?,
    ) {
        if (targetEventId == null) return
        appScope.launch {
            val late = try {
                waiter.await()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                return@launch
            }
            val outcome = paymentOutcome(late)
            val state = NwcPaymentPolicy.classify(outcome)
            if (state == NwcPaymentState.SUCCEEDED) {
                runCatching { refreshBalance() }
            }
            if (state != NwcPaymentState.UNKNOWN) {
                _latePayments.emit(NwcLatePayment(targetEventId, state))
            }
        }
    }

    private fun paymentOutcome(response: NwcRpcResponse): Result<Unit> {
        val body = interpretResponse("pay_invoice", response).getOrElse { return Result.failure(it) }
        val preimage = body["preimage"]?.jsonPrimitive?.contentOrNull
        return if (preimage.isNullOrBlank()) {
            Result.failure(NwcPaymentTimeoutException())
        } else {
            Result.success(Unit)
        }
    }

    suspend fun refreshWalletInfo(): Result<NwcWalletInfo> {
        val result = call("get_info", buildJsonObject {})
        return result.map { body ->
            val extensions = body["extensions"]?.jsonArray
                ?.flatMap { it.jsonPrimitive.contentOrNull?.split(Regex("\\s+")) ?: emptyList() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
            supportsNwcMetadata = supportsNwcMetadata || NwcCapabilities.METADATA_EXTENSION in extensions
            val info = NwcWalletInfo(
                alias = body["alias"]?.jsonPrimitive?.contentOrNull,
                network = body["network"]?.jsonPrimitive?.contentOrNull,
                methods = body["methods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                extensions = extensions,
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
    suspend fun payInvoice(
        invoice: String,
        zapRequest: Event? = null,
        recipientIdentifier: String? = null,
        comment: String? = null,
        targetEventId: String? = null,
    ): Result<Unit> {
        if (LightningInvoice.amountMsats(invoice) == null) {
            return Result.failure(IllegalArgumentException("wallet returned an invalid or amountless Lightning invoice"))
        }
        val metadata = if (supportsNwcMetadata && zapRequest != null) {
            buildJsonObject {
                recipientIdentifier?.trim()?.takeIf { it.isNotEmpty() }?.let { identifier ->
                    put("recipient_data", buildJsonObject { put("identifier", identifier) })
                }
                comment?.takeIf { it.isNotBlank() }?.let { put("comment", it) }
                put("nostr", json.parseToJsonElement(json.encodeToString(Event.serializer(), zapRequest)))
            }.takeIf { it.toString().length <= 4_096 }
        } else {
            null
        }
        val body = call(
            method = "pay_invoice",
            params = buildJsonObject {
                put("invoice", invoice.trim())
                metadata?.let { put("metadata", it) }
            },
            timeoutMs = 120_000,
            lateTargetEventId = targetEventId,
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
                id = "outgoing-$eventId-${UUID.randomUUID().toString().take(8)}",
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
                tag[0].jsonPrimitive.contentOrNull.equals("encryption", ignoreCase = true) &&
                NwcCapabilities.supportsNip44(
                    tag[1].jsonPrimitive.contentOrNull.orEmpty().split(Regex("\\s+")),
                )
        }

    private fun Event.advertisedExtensions(): Set<String> =
        tags
            .filter { tag ->
                tag.size >= 2 && tag[0].jsonPrimitive.contentOrNull.equals("extensions", ignoreCase = true)
            }
            .flatMap { tag -> tag[1].jsonPrimitive.contentOrNull.orEmpty().split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .toSet()

    private fun Event.walletInfo(): NwcWalletInfo {
        val body = runCatching { json.parseToJsonElement(content).jsonObject }.getOrNull()
        return NwcWalletInfo(
            alias = body?.get("alias")?.jsonPrimitive?.contentOrNull,
            network = body?.get("network")?.jsonPrimitive?.contentOrNull,
        )
    }
}
