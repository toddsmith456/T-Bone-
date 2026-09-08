package social.tbone.account.signer

import social.tbone.nostr.Event
import social.tbone.nostr.UnsignedEvent

/**
 * Abstraction over all signing backends.
 *
 * The app NEVER handles private key material directly.
 * Implementations delegate to Amber (NIP-55), nsecBunker (NIP-46),
 * or the Android Keystore (local key, last resort).
 */
interface NostrSigner {
    val pubkey: String

    /**
     * Signs [event] and returns the fully populated [Event] with id and sig set.
     * Implementations may suspend (e.g. waiting for an intent result or relay response).
     */
    suspend fun signEvent(event: UnsignedEvent): Result<Event>

    /**
     * Encrypts [plaintext] for [recipientPubkey] using NIP-44 versioned encryption.
     * Returns the ciphertext string.
     */
    suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String>

    /**
     * Decrypts [ciphertext] from [senderPubkey] using NIP-44 versioned encryption.
     */
    suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String>
}
