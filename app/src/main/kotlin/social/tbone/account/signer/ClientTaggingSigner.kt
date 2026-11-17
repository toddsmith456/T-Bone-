package social.tbone.account.signer

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import social.tbone.nostr.Event
import social.tbone.nostr.UnsignedEvent

/** NIP-89 client tag decorator, modeled on Amethyst's signer wrapper. */
class ClientTaggingSigner(
    private val inner: NostrSigner,
    private val enabled: () -> Boolean,
) : NostrSigner {
    override val pubkey: String get() = inner.pubkey

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> =
        inner.signEvent(ClientEventTag.decorate(event, enabled()))

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        inner.nip44Encrypt(plaintext, recipientPubkey)

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        inner.nip44Decrypt(ciphertext, senderPubkey)
}

/** Pure tag construction kept separate so the exact signed template is easy to test. */
object ClientEventTag {
    const val TAG_NAME = "client"
    const val CLIENT_NAME = "T-Bone"

    fun decorate(event: UnsignedEvent, enabled: Boolean): UnsignedEvent {
        // Never mutate an event that already carries a NIP-13 PoW nonce tag:
        // appending a client tag changes the event id and destroys the mined PoW.
        if (!enabled || event.tags.any(::isClientTag) || event.tags.any(::isNonceTag)) return event
        val clientTag = buildJsonArray {
            add(JsonPrimitive(TAG_NAME))
            add(JsonPrimitive(CLIENT_NAME))
        }
        return event.copy(tags = event.tags + listOf(clientTag))
    }

    private fun isClientTag(tag: JsonArray): Boolean =
        (tag.getOrNull(0) as? JsonPrimitive)?.content == TAG_NAME &&
            (tag.getOrNull(1) as? JsonPrimitive)?.content?.isNotEmpty() == true

    private fun isNonceTag(tag: JsonArray): Boolean =
        (tag.getOrNull(0) as? JsonPrimitive)?.content == "nonce"
}
