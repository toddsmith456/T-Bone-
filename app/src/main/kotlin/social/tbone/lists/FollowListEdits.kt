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
    private val stateLock = Any()
    @Volatile var paused: Boolean = false
        private set
    private var activeJob: kotlinx.coroutines.Job? = null

    /** Called inside mutex, before a potentially suspended relay/signer operation. */
    fun begin(job: kotlinx.coroutines.Job): Boolean = synchronized(stateLock) {
        if (paused) false else { activeJob = job; true }
    }

    fun finish(job: kotlinx.coroutines.Job) = synchronized(stateLock) {
        if (activeJob === job) activeJob = null
    }

    suspend fun pauseForWipe() {
        synchronized(stateLock) {
            paused = true
            activeJob?.cancel(kotlinx.coroutines.CancellationException("wipe requested"))
        }
        // The cancellation is cooperative; wait for the active mutation's
        // finally block before clearing the in-memory event cache.
        mutex.withLock { lastPublished.clear() }
    }

    suspend fun resumeAfterWipe() = mutex.withLock {
        synchronized(stateLock) { paused = false }
    }

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
