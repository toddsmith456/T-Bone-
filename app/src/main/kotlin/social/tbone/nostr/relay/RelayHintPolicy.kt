package social.tbone.nostr.relay

import social.tbone.Tunables
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import java.net.URI

/**
 * Policy & extraction helpers for relay hints embedded in Nostr events
 * (`q` tags, `e` tags, and NIP-19 `nevent1` / `naddr1` TLV fields).
 *
 * Untrusted hints from remote events are strictly validated before any
 * temporary read-only connection is opened:
 *  - Public relays must use `wss://` (no cleartext `ws://`).
 *  - `.onion` hints are permitted only while Tor mode is enabled.
 *  - Loopback, RFC-1918 private networks, link-local, CGNAT, and local
 *    hostnames are rejected to prevent SSRF/LAN probing.
 *  - Metadata-only relays (such as `wss://purplepag.es`) are excluded from
 *    note lookups so their fast empty EOSE cannot prematurely end fetches.
 */
object RelayHintPolicy {

    private val METADATA_ONLY_NORMALIZED: Set<String> =
        Tunables.METADATA_ONLY_RELAYS.map { RelayPool.normalize(it).lowercase() }.toSet()

    private val INLINE_NEVENT_OR_NOTE = Regex(
        "(?:nostr:)?(nevent1[02-9ac-hj-np-z]+|note1[02-9ac-hj-np-z]+)",
        RegexOption.IGNORE_CASE,
    )

    private const val MAX_HINT_URL_LENGTH = 256
    private const val MAX_HINTS_PER_EVENT = 4
    private const val MAX_Q_TAGS = 32

    /** True when [url] is known to serve only profile/relay metadata, never kind-1 notes. */
    fun isMetadataOnlyRelay(url: String): Boolean =
        RelayPool.normalize(url).lowercase() in METADATA_ONLY_NORMALIZED

    /** True when [url] can plausibly hold general Nostr note events. */
    fun isNoteCapableRelay(url: String): Boolean = !isMetadataOnlyRelay(url)

    /**
     * Validates and normalizes an untrusted relay hint URL from a remote event.
     * Returns null if the URL is malformed, unsafe (private/local IP), wrong
     * scheme, metadata-only, or `.onion` while Tor is off.
     */
    fun sanitizeHintUrl(raw: String?, torEnabled: Boolean): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = RelayPool.normalize(raw)
        if (trimmed.isEmpty() || trimmed.length > MAX_HINT_URL_LENGTH) return null
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
        if (!RelayPool.isValidRelayUrl(trimmed)) return null

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.trim('[', ']')?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (uri.userInfo != null) return null

        val isOnion = host.endsWith(".onion")
        if (isOnion) {
            if (!torEnabled) return null
            if (scheme != "ws" && scheme != "wss") return null
        } else {
            if (scheme != "wss") return null
            if (isPrivateOrLocalHost(host)) return null
            // Require a real public domain with a dot (rejects bare LAN hostnames like "nas").
            if ('.' !in host) return null
        }

        if (isMetadataOnlyRelay(trimmed)) return null
        return trimmed
    }

    /** True for loopback, private LAN, link-local, or non-routable hosts. */
    internal fun isPrivateOrLocalHost(rawHost: String): Boolean {
        val host = rawHost.trim('[', ']').lowercase()
        if (host.isEmpty()) return true
        if (host == "localhost" || host == "ip6-localhost" || host == "ip6-loopback") return true
        if (host.endsWith(".localhost") || host.endsWith(".local") ||
            host.endsWith(".internal") || host.endsWith(".lan") ||
            host.endsWith(".home.arpa")
        ) return true

        // IPv6 literals contain ':'
        if (':' in host) {
            if (host == "::1" || host == "::" || host == "0:0:0:0:0:0:0:1" || host == "0:0:0:0:0:0:0:0") return true
            if (host.startsWith("fc") || host.startsWith("fd")) return true // Unique local (ULA)
            if (host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb")
            ) return true // Link-local fe80::/10
            if (host.startsWith("::ffff:")) {
                val mapped = host.removePrefix("::ffff:")
                return isPrivateOrLocalHost(mapped)
            }
            return false
        }

        // IPv4 literal check
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) {
            val a = octets[0].toInt()
            val b = octets[1].toInt()
            return when {
                a == 0 -> true                      // 0.0.0.0/8
                a == 10 -> true                     // 10.0.0.0/8
                a == 127 -> true                    // 127.0.0.0/8 loopback
                a == 169 && b == 254 -> true        // 169.254.0.0/16 link-local
                a == 172 && b in 16..31 -> true     // 172.16.0.0/12
                a == 192 && b == 168 -> true        // 192.168.0.0/16
                a == 100 && b in 64..127 -> true    // 100.64.0.0/10 CGNAT
                a >= 224 -> true                    // multicast / reserved
                else -> false
            }
        }
        return false
    }

    /**
     * Extracts referenced event IDs and their raw relay hint URLs from [event]:
     *  - Kind-6 and Kind-16 repost `e` tags (`["e", id, relayHint]`)
     *  - `q` tags (`["q", id, relayHint, pubkey]`)
     *  - `e` mention tags (`["e", id, relayHint, "mention"]`)
     *  - Inline `nostr:nevent1…` (including TLV type-1 relay hints) & `nostr:note1…`
     */
    fun extractReferencedEventHints(event: Event): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        fun record(id: String?, hints: List<String> = emptyList()) {
            val cleanId = id?.trim()?.lowercase()?.takeIf { it.length == 64 } ?: return
            val bucket = result.getOrPut(cleanId) { mutableListOf() }
            for (hint in hints) {
                val trimmed = hint.trim()
                if (trimmed.isNotEmpty() && trimmed !in bucket && bucket.size < MAX_HINTS_PER_EVENT) {
                    bucket += trimmed
                }
            }
        }

        when (event.kind) {
            EventKind.REPOST, EventKind.GENERIC_REPOST -> {
                val eTag = event.parsedTags.firstOrNull { it.name == "e" }
                if (eTag != null) {
                    val hint = eTag.value(2)?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
                    record(eTag.value(1), listOfNotNull(hint))
                }
            }
            EventKind.TEXT_NOTE, EventKind.COMMENT, EventKind.POLL -> {
                var tagCount = 0
                for (tag in event.parsedTags) {
                    if (tagCount >= MAX_Q_TAGS) break
                    when {
                        tag.name == "q" -> {
                            val hint = tag.value(2)?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
                            record(tag.value(1), listOfNotNull(hint))
                            tagCount++
                        }
                        tag.name == "e" && (tag.value(3) == "mention" || tag.value(2) == "mention") -> {
                            val hint = tag.value(2)?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
                            record(tag.value(1), listOfNotNull(hint))
                            tagCount++
                        }
                    }
                }
                INLINE_NEVENT_OR_NOTE.findAll(event.content).forEach { match ->
                    val token = match.groupValues[1]
                    if (token.startsWith("nevent1", ignoreCase = true)) {
                        Nip19.neventToReference(token)?.let { ref ->
                            record(ref.idHex, ref.relayHints)
                        }
                    } else {
                        Nip19.noteToHex(token)?.let { id ->
                            record(id)
                        }
                    }
                }
            }
            else -> {
                // Notifications / replies / reactions can still carry e/q tags with relay hints.
                for (tag in event.parsedTags) {
                    if (tag.name == "e" || tag.name == "q") {
                        val hint = tag.value(2)?.takeIf { it.startsWith("ws://") || it.startsWith("wss://") }
                        if (hint != null) record(tag.value(1), listOf(hint))
                    }
                }
            }
        }
        return result
    }
}

/**
 * Lightweight bounded in-memory cache of raw relay hints discovered for event IDs
 * while rendering feeds, profiles, notifications, and threads.
 *
 * Lets tapping any quoted/reposted/referenced note carry its relay hints into
 * [social.tbone.ui.thread.ThreadViewModel] without changing navigation routes.
 */
object RelayHintCache {
    private const val MAX_ENTRIES = 2_000
    private const val MAX_HINTS_PER_ID = 4
    private val cache = object : LinkedHashMap<String, List<String>>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean =
            size > MAX_ENTRIES
    }

    fun put(eventId: String, hints: Collection<String>) {
        val cleanId = eventId.trim().lowercase()
        if (cleanId.length != 64 || hints.isEmpty()) return
        synchronized(cache) {
            val existing = cache[cleanId].orEmpty()
            val merged = (existing + hints.map { it.trim() }.filter { it.isNotEmpty() })
                .distinct()
                .take(MAX_HINTS_PER_ID)
            if (merged.isNotEmpty()) cache[cleanId] = merged
        }
    }

    fun putAll(hintsById: Map<String, List<String>>) {
        hintsById.forEach { (id, hints) -> put(id, hints) }
    }

    fun get(eventId: String): List<String> = synchronized(cache) {
        cache[eventId.trim().lowercase()].orEmpty()
    }

    fun clear() = synchronized(cache) {
        cache.clear()
    }
}
