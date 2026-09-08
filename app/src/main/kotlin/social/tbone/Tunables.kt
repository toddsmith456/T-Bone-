package social.tbone

/**
 * Central home for magic numbers and configuration constants.
 * One place to tune behaviour without grepping the whole codebase.
 */
object Tunables {

    // ── Feed ──────────────────────────────────────────────────────────────────

    /** Maximum events kept in the home/global feed list. */
    const val MAX_FEED_EVENTS = 300

    /** Maximum size of a kind-6 repost content field before parsing is skipped. */
    const val MAX_REPOST_CONTENT_BYTES = 64 * 1024

    // ── Relay selection ───────────────────────────────────────────────────────

    /** Maximum number of outbox relays selected by the greedy cover algorithm. */
    const val MAX_OUTBOX_RELAYS = 7

    /**
     * Hard ceiling on total connected relays (user-published list + outbox selection).
     * Without a cap, NIP-65 lists with a dozen+ entries combined with the outbox
     * picks can result in 19+ live connections — wasteful, duplicates events, and
     * keeps trying dead relays. Cap is applied after de-duplication so we keep
     * the highest-priority relays (user's list first, then outbox).
     */
    const val MAX_TOTAL_RELAYS = 8

    /** Fallback relays used when the account has no NIP-65 relay list. */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.nostr.band",
        "wss://nos.lol",
    )

    /** NIP-50 search relay. */
    const val SEARCH_RELAY = "wss://relay.nostr.band"

    // ── Tor / proxy ───────────────────────────────────────────────────────────

    /** Orbot HTTP CONNECT proxy port — preferred (no DNS leak). */
    const val TOR_HTTP_PROXY_PORT = 8118

    /** Orbot SOCKS proxy ports to probe when the HTTP proxy is unavailable. */
    val TOR_SOCKS_PORTS = listOf(9050, 9150)

    // ── Reconnect back-off ────────────────────────────────────────────────────

    /** Initial reconnect delay after a relay disconnects. */
    const val RECONNECT_DELAY_MS = 5_000L

    /** Maximum reconnect delay (exponential back-off ceiling). */
    const val MAX_RECONNECT_DELAY_MS = 60_000L

    // ── Subscription timeouts ─────────────────────────────────────────────────

    /** EOSE timeout for the NIP-50 search subscription. */
    const val SEARCH_EOSE_TIMEOUT_MS = 10_000L

    /** EOSE timeout for historical reaction subscriptions. */
    const val REACTION_EOSE_TIMEOUT_MS = 30_000L

    // ── Caches ────────────────────────────────────────────────────────────────

    /** Maximum verified event IDs kept in VerifiedEventCache. */
    const val VERIFIED_EVENT_CACHE_SIZE = 10_000

    /** Maximum profile entries kept in ProfileRepository's in-memory cache. */
    const val PROFILE_CACHE_SIZE = 1_000

    /** Maximum reaction entries (by event ID) kept in ReactionsRepository. */
    const val REACTION_CACHE_SIZE = 5_000
}
