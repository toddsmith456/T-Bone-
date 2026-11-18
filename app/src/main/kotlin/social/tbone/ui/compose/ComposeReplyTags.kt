package social.tbone.ui.compose

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.nostr.Event
import social.tbone.nostr.replyToPubkeys
import social.tbone.nostr.rootEventId

/** Build explicit NIP-10 parent/root and participant tags for a reply target. */
internal fun nip10ReplyTags(parent: Event): List<JsonArray> = buildList {
    val rootId = parent.parsedTags.rootEventId
    if (rootId != null && rootId != parent.id) {
        add(buildJsonArray {
            add(JsonPrimitive("e")); add(JsonPrimitive(rootId))
            add(JsonPrimitive("")); add(JsonPrimitive("root"))
        })
    }
    add(buildJsonArray {
        add(JsonPrimitive("e")); add(JsonPrimitive(parent.id))
        add(JsonPrimitive("")); add(JsonPrimitive("reply"))
    })
    (listOf(parent.pubkey) + parent.parsedTags.replyToPubkeys).distinct().forEach { pubkey ->
        add(buildJsonArray { add(JsonPrimitive("p")); add(JsonPrimitive(pubkey)) })
    }
}
