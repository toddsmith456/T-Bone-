package social.tbone.lists

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import social.tbone.account.FollowEntry
import social.tbone.nostr.Event

/** Preserve untouched raw tags, including extensions and tags we cannot parse. */
internal fun followTagsForToggle(
    current: Event?, fallback: List<FollowEntry>, target: String, add: Boolean,
): List<JsonArray> {
    val original = current?.tags ?: fallback.map { entry ->
        buildJsonArray {
            add("p"); add(entry.pubkey)
            if (entry.relay.isNotEmpty() || entry.petname.isNotEmpty()) add(entry.relay)
            if (entry.petname.isNotEmpty()) add(entry.petname)
        }
    }
    fun isTarget(tag: JsonArray): Boolean =
        (tag.getOrNull(0) as? JsonPrimitive)?.content == "p" &&
            (tag.getOrNull(1) as? JsonPrimitive)?.content?.equals(target, ignoreCase = true) == true
    if (add && original.any(::isTarget)) return original
    if (!add) return original.filterNot(::isTarget)
    return original + listOf(buildJsonArray { add("p"); add(target) })
}
