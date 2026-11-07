package social.tbone.ui.thread

/** Tracks authors already being requested by a one-shot metadata subscription. */
internal class MetadataLeases {
    private val pending = mutableSetOf<String>()
    private val bySubscription = mutableMapOf<String, Set<String>>()

    @Synchronized fun claim(id: String, authors: Collection<String>): List<String> {
        check(id !in bySubscription)
        val fresh = authors.distinct().filter { it.isNotBlank() && pending.add(it) }
        if (fresh.isNotEmpty()) bySubscription[id] = fresh.toSet()
        return fresh
    }

    @Synchronized fun contains(id: String): Boolean = id in bySubscription

    @Synchronized fun release(id: String): Boolean {
        val authors = bySubscription.remove(id) ?: return false
        pending.removeAll(authors)
        return true
    }
}
