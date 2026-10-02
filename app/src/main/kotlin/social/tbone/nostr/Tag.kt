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
 *
 * An "e" tag alone is NOT enough: NIP-27-style references carry
 * `["e", <id>, <relay>, "mention"]` on a note that is not a reply at all
 * (and quote-notes use "q"). Treating those as replies produced a bogus
 * "↳ replying to …" line on ordinary notes, so a mention-only e-tag set is
 * excluded here.
 */
val List<Tag>.isReply: Boolean
    get() {
        val eTags = filter { it.name == "e" }
        if (eTags.isEmpty()) return false
        return eTags.any { it.nip10Marker() != "mention" }
    }

/**
 * NIP-10: the pubkey(s) this note is replying to, in order.
 * Uses the "p" tags which represent mentioned/notified participants.
 * The first p-tag is conventionally the direct reply target's pubkey.
 */
val List<Tag>.replyToPubkeys: List<String>
    get() = pubkeys

/**
 * NIP-10: the direct parent event ID.
 *
 * Prefers the explicit "reply" marker, then falls back to positional NIP-10
 * (the last e-tag). Tags explicitly marked "mention" are skipped: they name a
 * referenced note, not the parent, and using one as the parent is what made
 * notes resolve into the wrong thread.
 */
val List<Tag>.replyEventId: String?
    get() {
        val eTags = filter { it.name == "e" }
        if (eTags.isEmpty()) return null
        eTags.firstOrNull { it.nip10Marker() == "reply" }?.let { return it.value() }
        return eTags.lastOrNull { it.nip10Marker() != "mention" }?.value()
    }

/**
 * NIP-22: the thread root of a comment (kind 1111), carried in the uppercase
 * "E" tag. Kind-1111 events use lowercase "e" for their direct parent, so
 * [replyEventId] still resolves the parent while this resolves the root.
 */
val List<Tag>.commentRootEventId: String?
    get() = firstOrNull { it.name == "E" }?.value()

/**
 * NIP-22: the root of a comment thread, or the NIP-10 root for other kinds.
 */
val List<Tag>.threadRootEventId: String?
    get() = rootEventId ?: commentRootEventId

/**
 * NIP-18: the quoted event ID for quote-notes (kind-1 with a "q" tag).
 */
val List<Tag>.quotedEventId: String?
    get() = firstOrNull { it.name == "q" }?.value()

/**
 * NIP-10: the root event ID of the thread.
 *
 * Prefers the explicit "root" marker, then falls back to positional NIP-10:
 * with several e-tags the first one is the root, and with a **single**
 * unmarked e-tag that tag is both root and reply target. Only an e-tag
 * explicitly marked "mention" is ignored — returning the mention instead put
 * notes into the wrong thread.
 */
val List<Tag>.rootEventId: String?
    get() {
        val eTags = filter { it.name == "e" }
        if (eTags.isEmpty()) return null
        eTags.firstOrNull { it.nip10Marker() == "root" }?.let { return it.value() }
        val first = eTags.first()
        if (first.nip10Marker() == "mention") return null
        return first.value()
    }

/**
 * Returns the NIP-10 marker ("root", "reply", "mention") for an "e" tag.
 * Marker may be at index 3 (relay hint present) or index 2 (relay hint omitted).
 */
private fun Tag.nip10Marker(): String? {
    val markers = setOf("root", "reply", "mention")
    return listOfNotNull(value(3), value(2)).firstOrNull { it in markers }
}
