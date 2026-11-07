package social.tbone.ui.thread

import kotlinx.coroutines.Job

/** Tracks authors already being requested by a one-shot metadata subscription. */
internal class MetadataLeases {
    private val pending = mutableSetOf<String>()
    private val bySubscription = mutableMapOf<String, Set<String>>()
    private val timeouts = mutableMapOf<String, Job>()

    @Synchronized fun claim(id: String, authors: Collection<String>): List<String> {
        check(id !in bySubscription)
        val fresh = authors.distinct().filter { it.isNotBlank() && pending.add(it) }
        if (fresh.isNotEmpty()) bySubscription[id] = fresh.toSet()
        return fresh
    }

    @Synchronized fun contains(id: String): Boolean = id in bySubscription

    /** A late timer cannot keep an already-closed request alive. */
    @Synchronized fun attachTimeout(id: String, job: Job): Boolean {
        if (id !in bySubscription) { job.cancel(); return false }
        check(id !in timeouts)
        timeouts[id] = job
        return true
    }

    @Synchronized fun release(id: String): Boolean {
        val authors = bySubscription.remove(id) ?: return false
        timeouts.remove(id)?.cancel()
        pending.removeAll(authors)
        return true
    }
}
