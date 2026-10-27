package social.tbone.lists

import social.tbone.media.blossom.BlossomServerUrl
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.relay.RelayPool
import java.net.URI

/** Parsers for relay-published preference lists used by the settings sync actions. */
object PublishedRelayLists {
    const val MAX_BLOSSOM_SERVERS = 100
    const val MAX_PREFERRED_RELAYS = 100

    /** BUD-03 kind 10063: one canonical Blossom base URL per `server` tag. */
    fun blossomServers(event: Event): List<String> {
        if (event.kind != EventKind.BLOSSOM_SERVER_LIST) return emptyList()
        return event.parsedTags.asSequence()
            .filter { it.name == "server" }
            .mapNotNull { it.value()?.let(BlossomServerUrl::normalize) }
            .distinct()
            .take(MAX_BLOSSOM_SERVERS)
            .toList()
    }

    /**
     * NIP-65 kind 10002 — full relay list, exactly as published.
     * Includes all relays regardless of read/write marker (unmarked = both).
     * This is what sync must use to get the entire list — Amethyst treats
     * the relay list as read+write combined, not read-only.
     */
    fun allRelays(event: Event): List<String> {
        if (event.kind != EventKind.RELAY_LIST) return emptyList()
        return event.parsedTags.asSequence()
            .filter { it.name == "r" }
            .mapNotNull { tag -> tag.value()?.let(::normalizeRelayUrl) }
            .distinct()
            .take(MAX_PREFERRED_RELAYS)
            .toList()
    }

    /**
     * NIP-65 kind 10002 read preferences: include unmarked relays (read+write)
     * and `read` relays, and exclude `write`-only relays from the feed pool.
     * Kept for backward compat but prefer [allRelays] for sync.
     */
    fun preferredRelays(event: Event): List<String> {
        if (event.kind != EventKind.RELAY_LIST) return emptyList()
        return event.parsedTags.asSequence()
            .filter { it.name == "r" }
            .filter { tag -> tag.value(2).let { it == null || it == "read" } }
            .mapNotNull { tag -> tag.value()?.let(::normalizeRelayUrl) }
            .distinct()
            .take(MAX_PREFERRED_RELAYS)
            .toList()
    }

    /** All relays with their original markers preserved (for export fidelity). */
    fun allRelaysWithMarkers(event: Event): List<RelayFileFormat.RelayMarker> {
        if (event.kind != EventKind.RELAY_LIST) return emptyList()
        return event.parsedTags.asSequence()
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value()?.let(::normalizeRelayUrl) ?: return@mapNotNull null
                val marker = tag.value(2)?.lowercase()?.takeIf { it == "read" || it == "write" }
                RelayFileFormat.RelayMarker(url, marker)
            }
            .distinctBy { it.url }
            .take(MAX_PREFERRED_RELAYS)
            .toList()
    }

    private fun normalizeRelayUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "wss" && scheme != "ws") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return RelayPool.normalize(trimmed)
            .replaceFirst(Regex("^${Regex.escape(uri.scheme)}://"), "$scheme://")
    }
}
