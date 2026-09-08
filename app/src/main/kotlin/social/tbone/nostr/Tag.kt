package social.tbone.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A Nostr tag is a list of strings: ["e", "<event-id>", "<relay-url>", "<marker>"]
 * The first element is the tag name; remaining elements are tag-specific values.
 */
@JvmInline
value class Tag(val values: List<String>) {

    val name: String get() = values.firstOrNull() ?: ""

    fun value(index: Int = 1): String? = values.getOrNull(index)

    companion object {
        fun fromJsonArray(array: JsonArray): Tag =
            Tag(array.map { it.jsonPrimitive.contentOrNull ?: "" })

        // Convenience constructors for common tag types
        fun event(eventId: String, relayHint: String = "", marker: String = ""): Tag =
            Tag(listOfNotNull("e", eventId, relayHint.ifEmpty { null }, marker.ifEmpty { null }))

        fun pubkey(pubkey: String, relayHint: String = "", petname: String = ""): Tag =
            Tag(listOfNotNull("p", pubkey, relayHint.ifEmpty { null }, petname.ifEmpty { null }))

        fun relay(url: String, marker: String = ""): Tag =
            Tag(listOfNotNull("r", url, marker.ifEmpty { null }))
    }
}

val List<Tag>.eventIds: List<String>
    get() = filter { it.name == "e" }.mapNotNull { it.value() }

val List<Tag>.pubkeys: List<String>
    get() = filter { it.name == "p" }.mapNotNull { it.value() }

/**
 * NIP-10: returns true if this event is a reply to another note.
 * A note is a reply if it has any "e" tags.
 */
val List<Tag>.isReply: Boolean
    get() = any { it.name == "e" }

/**
 * NIP-10: the pubkey(s) this note is replying to, in order.
 * Uses the "p" tags which represent mentioned/notified participants.
 * The first p-tag is conventionally the direct reply target's pubkey.
 */
val List<Tag>.replyToPubkeys: List<String>
    get() = pubkeys

/**
 * NIP-10: the direct parent event ID.
 * Prefers the "reply" marker; falls back to positional (last e-tag).
 */
val List<Tag>.replyEventId: String?
    get() {
        val eTags = filter { it.name == "e" }
        if (eTags.isEmpty()) return null
        return eTags.firstOrNull { it.nip10Marker() == "reply" }?.value()
            ?: eTags.last().value()
    }

/**
 * NIP-18: the quoted event ID for quote-notes (kind-1 with a "q" tag).
 */
val List<Tag>.quotedEventId: String?
    get() = firstOrNull { it.name == "q" }?.value()

/**
 * NIP-10: the root event ID of the thread.
 * Prefers the "root" marker; falls back to positional (first e-tag when >1).
 */
val List<Tag>.rootEventId: String?
    get() {
        val eTags = filter { it.name == "e" }
        if (eTags.isEmpty()) return null
        return eTags.firstOrNull { it.nip10Marker() == "root" }?.value()
            ?: if (eTags.size > 1) eTags.first().value() else null
    }

/**
 * Returns the NIP-10 marker ("root", "reply", "mention") for an "e" tag.
 * Marker may be at index 3 (relay hint present) or index 2 (relay hint omitted).
 */
private fun Tag.nip10Marker(): String? {
    val markers = setOf("root", "reply", "mention")
    return listOfNotNull(value(3), value(2)).firstOrNull { it in markers }
}
