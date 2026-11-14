package social.tbone

/**
 * Central home for magic numbers and configuration constants.
 * One place to tune behaviour without grepping the whole codebase.
 */
object Tunables {

    // ── Feed ──────────────────────────────────────────────────────────────────

    /** Maximum events kept in the home/global feed list. */
    const val MAX_FEED_EVENTS = 300

    /** Maximum poll choices offered by the composer. */
    const val MAX_POLL_OPTIONS = 30

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

    /**
     * Relays seeded into the very first account on a fresh install. Existing
     * accounts with an empty relay list continue to use [DEFAULT_RELAYS].
     */
    val NEW_INSTALL_RELAYS = listOf(
        "wss://hotrightnow.nostr1.com",
        "wss://atlas.nostr.land",
        "wss://nostr.computingcache.com",
        "wss://nostr-02.yakihonne.com",
        "wss://relay.ditto.pub",
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nostr.bitcoiner.social",
        "wss://purplepag.es",
        "wss://relay.nostr.net",
        "wss://nostr-pub.wellorder.net",
    )

    /** Legacy fallback relays used when an existing account has no relay list. */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nos.lol",
        "wss://relay.nostr.band",
    )

    /**
     * Built-in Fediverse bridge relays (Ditto / Mostr).
     *
     * Note: `wss://relay.mostr.pub` now HTTP-301 redirects to `https://relay.ditto.pub/`
     * (which OkHttp WebSocket does not follow) and `wss://ditto.pub/relay` serves HTML,
     * so `wss://relay.ditto.pub` is the canonical live WebSocket endpoint for both
     * Ditto and Mostr bridged notes.
     *
     * Used for read-only fallback lookups when enabled in Relay Settings,
     * and offered as a one-tap preset to add to the user's personal relay list.
     */
    val FEDIVERSE_BRIDGE_RELAYS = listOf(
        "wss://relay.ditto.pub",
    )

    /**
     * Relays that store only profile/relay-list metadata (kinds 0 / 10002),
     * never kind-1 notes. Their fast empty EOSE must never prematurely finish
     * a note or quote lookup while general relays are still answering.
     */
    val METADATA_ONLY_RELAYS = setOf(
        "wss://purplepag.es",
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
