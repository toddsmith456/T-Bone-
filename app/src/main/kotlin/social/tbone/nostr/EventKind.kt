package social.tbone.nostr

/**
 * Known Nostr event kinds per NIP-01 and others.
 * Unknown kinds are handled as raw integers — the protocol is open-ended.
 */
object EventKind {
    const val METADATA = 0           // NIP-01: user profile
    const val TEXT_NOTE = 1          // NIP-01: short text post
    const val FOLLOW_LIST = 3        // NIP-02: contact list
    const val ENCRYPTED_DM = 4       // NIP-04: encrypted direct message (legacy)
    const val DELETE = 5             // NIP-09: event deletion
    const val REPOST = 6             // NIP-18: repost
    const val REACTION = 7           // NIP-25: reaction (like/+1)
    const val SEAL = 13              // NIP-59: sealed event wrapper
    const val GIFT_WRAP = 1059       // NIP-59: gift wrap
    const val PRIVATE_DM = 14        // NIP-17: private direct message
    const val POLL_RESPONSE = 1018   // NIP-88: poll vote
    const val POLL = 1068            // NIP-88: poll
    const val RELAY_LIST = 10002     // NIP-65: relay list metadata
    const val AUTH = 22242           // NIP-42: relay authentication
    const val NOSTR_CONNECT = 24133  // NIP-46: nsecBunker request/response
}
