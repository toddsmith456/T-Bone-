package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

/**
 * NIP-09 deletion requests (kind 5).
 *
 * A deletion request only has meaning when signed by the same key that
 * authored the target event: relays must ignore requests from anyone else.
 * It is a request, not a guarantee — relays may honour it, ignore it, or
 * have already shared the event with other relays and clients.
 *
 * Mirrors what other clients send: an `e` tag for the event, an `a` tag
 * additionally for addressable (NIP-33) events, and a `k` tag naming the
 * kind being deleted.
 */
object Nip09Deletion {
    const val KIND = EventKind.DELETE

    fun forNote(
        requesterPubkey: String,
        target: Event,
        createdAtSeconds: Long = System.currentTimeMillis() / 1000L,
    ): UnsignedEvent {
        val tags = buildList {
            add(tag("e", target.id))
            addressOf(target)?.let { add(tag("a", it)) }
            add(tag("k", target.kind.toString()))
        }
        return UnsignedEvent(
            pubkey = requesterPubkey,
            createdAt = createdAtSeconds,
            kind = KIND,
            tags = tags,
            content = "",
        )
    }

    private fun addressOf(event: Event): String? {
        // `a` tags address parameterized replaceable events (NIP-33).
        if (event.kind !in 30_000..39_999) return null
        val d = event.parsedTags.firstOrNull { it.name == "d" }?.value().orEmpty()
        return "${event.kind}:${event.pubkey}:$d"
    }

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
