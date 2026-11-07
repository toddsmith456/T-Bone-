package social.tbone.lists

import kotlinx.coroutines.sync.withLock
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

/** Serializes this process's follow replacements and remembers accepted local sends. */
@javax.inject.Singleton
class FollowMutationCoordinator @javax.inject.Inject constructor() {
    val mutex = kotlinx.coroutines.sync.Mutex()
    private val lastPublished = mutableMapOf<String, Event>()
    var paused: Boolean = false
        private set

    suspend fun pauseForWipe() = mutex.withLock {
        paused = true
        lastPublished.clear()
    }

    suspend fun resumeAfterWipe() = mutex.withLock { paused = false }

    // Both methods are called under mutex; this is intentionally an in-memory
    // safeguard, not a distributed transaction or a persistent relay receipt.
    fun newest(owner: String, remote: Event?): Event? {
        val local = lastPublished[owner] ?: return remote
        return if (remote == null) local else newestListEvent(local, remote)
    }

    fun published(owner: String, event: Event) {
        lastPublished[owner] = newestListEvent(lastPublished[owner], event)
    }
}
