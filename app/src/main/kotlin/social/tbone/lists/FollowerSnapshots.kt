package social.tbone.lists

/** A bounded union of imperfect samples, not a verified current follower count. */
internal class FollowerSamples(private val maxEntries: Int = 5_000) {
    private val entries = linkedSetOf<String>()
    private val sources = linkedSetOf<String>()

    @Synchronized fun merge(values: Collection<String>, source: String): FollowerListRepository.FollowersState? {
        if (values.isEmpty()) return null
        val previousSize = entries.size
        val wasNewSource = sources.add(source)
        for (value in values) {
            if (entries.size >= maxEntries) break
            entries.add(value.lowercase())
        }
        if (entries.size == previousSize && !wasNewSource) return null
        return FollowerListRepository.FollowersState(
            followers = entries.toList(), loading = false, source = sources.sorted().joinToString(" + "),
        )
    }
}

/** Five-minute cache scoped to the profile AND relay selection. */
internal class FollowerSnapshotCache(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry(val state: FollowerListRepository.FollowersState, val savedAt: Long)
    private val entries = LinkedHashMap<Pair<String, List<String>>, Entry>()
    private var generation = 0L
    private fun key(pubkey: String, relays: List<String>) = pubkey to relays.map { it.trim().trimEnd('/') }.distinct().sorted()

    @Synchronized fun generation(): Long = generation
    @Synchronized fun get(pubkey: String, relays: List<String>): FollowerListRepository.FollowersState? {
        val key = key(pubkey, relays)
        val entry = entries[key] ?: return null
        val age = clock() - entry.savedAt
        if (age < 0 || age >= 5 * 60_000L) { entries.remove(key); return null }
        return entry.state
    }
    @Synchronized fun put(pubkey: String, relays: List<String>, state: FollowerListRepository.FollowersState, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        val key = key(pubkey, relays)
        entries.remove(key)
        entries[key] = Entry(state, clock())
        while (entries.size > 128) entries.remove(entries.keys.first())
    }
    @Synchronized fun invalidate(pubkey: String) {
        generation++
        entries.keys.removeAll { it.first == pubkey }
    }
    @Synchronized fun clear() { generation++; entries.clear() }
}
