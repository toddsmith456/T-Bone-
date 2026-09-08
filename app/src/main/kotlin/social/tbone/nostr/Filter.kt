package social.tbone.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * A Nostr subscription filter per NIP-01.
 * Null fields are omitted from the serialized JSON sent to relays.
 */
@Serializable
data class Filter(
    val ids: List<String>? = null,
    val authors: List<String>? = null,
    val kinds: List<Int>? = null,
    @SerialName("since") val since: Long? = null,
    @SerialName("until") val until: Long? = null,
    val limit: Int? = null,
    @SerialName("#e") val eTags: List<String>? = null,
    @SerialName("#p") val pTags: List<String>? = null,
    @SerialName("#t") val tTags: List<String>? = null,
    @SerialName("#g") val gTags: List<String>? = null,
    val search: String? = null,  // NIP-50: full-text search (relay-dependent)
) {
    fun toJsonObject(): JsonObject {
        val full = Json.encodeToJsonElement(this).jsonObject
        // Drop null fields — relays treat absent fields as "match all"
        return JsonObject(full.filterValues { it !is JsonNull })
    }

    companion object {
        /** Convenience: home feed for a set of pubkeys. */
        fun homeFeed(pubkeys: List<String>, limit: Int = 50) = Filter(
            authors = pubkeys,
            kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL),
            limit = limit,
        )

        /** Convenience: profile metadata for a pubkey. */
        fun profileMetadata(pubkey: String) = Filter(
            authors = listOf(pubkey),
            kinds = listOf(EventKind.METADATA),
            limit = 1,
        )

        /** Convenience: notifications (replies, mentions, reactions, reposts, follows, votes) for a pubkey. */
        fun notifications(pubkey: String, limit: Int = 50) = Filter(
            kinds = listOf(
                EventKind.TEXT_NOTE,
                EventKind.REACTION,
                EventKind.REPOST,
                EventKind.POLL_RESPONSE,
                EventKind.FOLLOW_LIST,
            ),
            pTags = listOf(pubkey),
            limit = limit,
        )
    }
}
