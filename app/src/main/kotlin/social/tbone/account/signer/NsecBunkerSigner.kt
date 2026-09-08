package social.tbone.account.signer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import social.tbone.account.NsecBunkerConfig
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.relay.ClientMessage
import social.tbone.nostr.relay.PoolMessage
import social.tbone.nostr.relay.RelayMessage
import social.tbone.nostr.relay.RelayPool
import java.util.UUID

private const val REQUEST_TIMEOUT_MS = 30_000L

/**
 * NIP-46: delegates signing and encryption to a remote nsecBunker
 * communicating over a Nostr relay via encrypted kind-24133 events.
 *
 * The session keypair ([config.sessionPubkey]) is ephemeral and generated
 * at account setup time. The private half is stored in Android Keystore
 * (managed by [LocalKeySigner]) and used only for NIP-44 encryption of
 * the NIP-46 request/response channel — NOT for signing Nostr events.
 */
class NsecBunkerSigner(
    override val pubkey: String,
    private val config: NsecBunkerConfig,
    private val pool: RelayPool,
    private val sessionSigner: LocalKeySigner, // signs the NIP-46 wrapper events
    private val signingIndicator: SigningIndicator,
) : NostrSigner {

    /**
     * NIP-46 connect handshake. Call this once after construction to authenticate
     * with the bunker and retrieve the user's actual public key.
     * Returns the user's hex pubkey on success.
     */
    suspend fun handshake(): Result<String> = runCatching {
        val connectResult = request("connect", listOf(config.sessionPubkey, config.secret)).getOrThrow()
        check(connectResult == "ack") { "Bunker rejected connect: $connectResult" }
        request("get_public_key", emptyList()).getOrThrow()
    }

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> =
        request("sign_event", listOf(unsignedEventToJson(event)))
            .mapCatching { result -> Event.fromJson(result) }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        request("nip44_encrypt", listOf(plaintext, recipientPubkey))

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        request("nip44_decrypt", listOf(ciphertext, senderPubkey))

    // ── NIP-46 wire protocol ──────────────────────────────────────────────────

    private suspend fun request(method: String, params: List<String>): Result<String> {
        signingIndicator.start()
        return try {
            Result.success(performRequest(method, params))
        } catch (e: Throwable) {
            Result.failure(e)
        } finally {
            signingIndicator.stop()
        }
    }

    private suspend fun performRequest(method: String, params: List<String>): String {
            val requestId = UUID.randomUUID().toString().take(8)
            val requestJson = Json.encodeToString(
                Nip46Request.serializer(),
                Nip46Request(id = requestId, method = method, params = params),
            )

            // Encrypt request content for the bunker pubkey
            val encryptedContent = sessionSigner
                .nip44Encrypt(requestJson, config.bunkerPubkey)
                .getOrThrow()

            // Build and publish the NIP-46 request event
            val requestEvent = sessionSigner.signEvent(
                UnsignedEvent(
                    pubkey = config.sessionPubkey,
                    kind = EventKind.NOSTR_CONNECT,
                    tags = listOf(
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("p"))
                            add(kotlinx.serialization.json.JsonPrimitive(config.bunkerPubkey))
                        }
                    ),
                    content = encryptedContent,
                )
            ).getOrThrow()

            pool.addRelay(config.relayUrl)
            pool.subscribe(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.NOSTR_CONNECT),
                        authors = listOf(config.bunkerPubkey),
                        pTags = listOf(config.sessionPubkey),
                    )
                ),
            )

            pool.send(config.relayUrl, ClientMessage.Publish(requestEvent))

            // Await the response matching our requestId
            return withTimeout(REQUEST_TIMEOUT_MS) {
                pool.messages
                    .filter { it.relayUrl == config.relayUrl }
                    .filter { it.message is RelayMessage.EventMessage }
                    .first { poolMessage ->
                        val event = (poolMessage.message as RelayMessage.EventMessage).event
                        parseResponse(event, requestId) != null
                    }
                    .let { poolMessage ->
                        val event = (poolMessage.message as RelayMessage.EventMessage).event
                        val response = parseResponse(event, requestId)!!
                        if (response.error.isNotEmpty()) {
                            error("nsecBunker error: ${response.error}")
                        }
                        response.result
                    }
            }
        }

    private fun parseResponse(event: Event, requestId: String): Nip46Response? = runCatching {
        val decrypted = sessionSigner
            .nip44DecryptSync(event.content, config.bunkerPubkey)
            ?: return null
        val response = Json.decodeFromString(Nip46Response.serializer(), decrypted)
        if (response.id == requestId) response else null
    }.getOrNull()

    private fun unsignedEventToJson(event: UnsignedEvent): String =
        Json.encodeToString(UnsignedEvent.serializer(), event)

    @Serializable
    private data class Nip46Request(
        val id: String,
        val method: String,
        val params: List<String>,
    )

    @Serializable
    private data class Nip46Response(
        val id: String,
        val result: String,
        val error: String = "",
    )
}
