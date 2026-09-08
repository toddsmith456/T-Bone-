package social.tbone.account

import kotlinx.serialization.Serializable

/** Full NIP-02 contact entry: pubkey plus optional relay hint and petname. */
@Serializable
data class FollowEntry(
    val pubkey: String,
    val relay: String = "",
    val petname: String = "",
)

/**
 * Represents one Nostr identity stored in the app.
 * The private key is never stored here — signing is always delegated.
 */
@Serializable
data class Account(
    val pubkey: String,                          // hex-encoded 32-byte public key
    val displayName: String? = null,             // cached from kind-0 metadata
    val pictureUrl: String? = null,              // cached from kind-0 metadata
    val signerType: SignerType,
    val nsecBunkerConfig: NsecBunkerConfig? = null,
    val relays: List<String> = emptyList(),      // NIP-65 outbox relays
    val follows: List<String> = emptyList(),     // pubkey-only list for fast lookups (legacy / compat)
    val followEntries: List<FollowEntry> = emptyList(), // full NIP-02 entries with relay hints + petnames
) {
    /** True if [pubkey] is in the follow list. */
    fun isFollowing(pubkey: String): Boolean =
        follows.contains(pubkey) || followEntries.any { it.pubkey == pubkey }
}

@Serializable
enum class SignerType {
    /** NIP-55: delegates to Amber (or any compatible signer app) via Android intents. */
    AMBER,

    /** NIP-46: delegates to a remote nsecBunker over a Nostr relay. */
    NSEC_BUNKER,

    /**
     * Local key stored in Android Keystore.
     * Last resort — only offered when no external signer is available.
     */
    LOCAL_KEY,
}

@Serializable
data class NsecBunkerConfig(
    val bunkerPubkey: String,   // hex pubkey of the bunker
    val relayUrl: String,       // relay the bunker is listening on
    val secret: String,         // optional shared secret for connect handshake
    val sessionPubkey: String,  // ephemeral session pubkey (hex) for this client
)
