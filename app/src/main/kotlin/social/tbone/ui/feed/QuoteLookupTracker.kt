package social.tbone.ui.feed

/**
 * Pure per-ID lifecycle tracker for quoted/reposted/referenced note lookups.
 *
 * Previously, `refreshUnresolvedQuotes()` computed `requestedQuoteIds - resolved`,
 * which meant resolving ANY single quote (from local cache or a fast relay)
 * immediately stamped EVERY other still-in-flight quote as
 * "this note isn't available on any relay".
 *
 * This tracker keeps three strictly separate states per ID:
 *  - **Resolved:** event has arrived (never unresolved).
 *  - **Pending / In-Flight:** queued for batching, awaiting cache lookup, or
 *    actively being queried on Stage-1 or Stage-2 relays (renders as "loading…").
 *  - **Terminal Unresolved:** ONLY entered once every active subscription (and
 *    any enabled fallback stage) for THAT SPECIFIC ID has completed without
 *    finding the event.
 */
internal class QuoteLookupTracker {

    private val lock = Any()
    private val resolvedIds = mutableSetOf<String>()
    private val queuedIds = mutableSetOf<String>()
    private val inFlightBySub = mutableMapOf<String, Set<String>>()
    private val terminalUnresolvedIds = mutableSetOf<String>()

    /**
     * Marks [ids] as queued for lookup if they are not already resolved,
     * queued, in-flight, or terminally unresolved. Returns only the newly
     * admitted IDs.
     */
    fun enqueue(ids: Collection<String>): List<String> = synchronized(lock) {
        val activeInFlight = inFlightBySub.values.flatten().toSet()
        val newlyQueued = mutableListOf<String>()
        for (raw in ids) {
            val id = raw.trim()
            if (id.isEmpty()) continue
            if (id in resolvedIds || id in queuedIds || id in activeInFlight || id in terminalUnresolvedIds) {
                continue
            }
            queuedIds += id
            newlyQueued += id
        }
        newlyQueued
    }

    /**
     * Records [ids] as resolved (from embedded repost, local cache, or relay).
     * Never marks unrelated queued or in-flight IDs as unresolved.
     * Returns the updated terminal-unresolved snapshot.
     */
    fun markResolved(ids: Collection<String>): Set<String> = synchronized(lock) {
        for (id in ids) {
            resolvedIds += id
            queuedIds -= id
            terminalUnresolvedIds -= id
        }
        terminalUnresolvedIds.toSet()
    }

    /** Moves [ids] from queued into an active subscription [subId]. */
    fun startSubscription(subId: String, ids: Collection<String>) = synchronized(lock) {
        val pending = ids.filterTo(mutableSetOf()) { it !in resolvedIds }
        queuedIds.removeAll(pending)
        if (pending.isNotEmpty()) {
            inFlightBySub[subId] = pending
        }
    }

    /**
     * Finishes subscription [subId].
     *
     * Any ID owned by [subId] that has NOT resolved:
     *  - If present in [keepPending] (e.g. moving into Stage-2 hint/bridge fallback),
     *    stays queued/loading rather than becoming unavailable.
     *  - If still covered by another active subscription or queued batch, stays loading.
     *  - When [markTerminal] is false (e.g. zero note-capable relays answered due
     *    to being offline or still connecting), releases the ID WITHOUT stamping it
     *    unavailable so a retry on reconnect can pick it up.
     *  - Otherwise transitions cleanly into [terminalUnresolvedIds].
     *
     * Returns the updated terminal-unresolved snapshot.
     */
    fun finishSubscription(
        subId: String,
        keepPending: Set<String> = emptySet(),
        markTerminal: Boolean = true,
    ): Set<String> = synchronized(lock) {
        val owned = inFlightBySub.remove(subId) ?: return terminalUnresolvedIds.toSet()
        val stillCoveredElsewhere = inFlightBySub.values.flatten().toSet()
        for (id in owned) {
            if (id in resolvedIds) continue
            if (id in keepPending) {
                queuedIds += id
                continue
            }
            if (id in queuedIds || id in stillCoveredElsewhere) continue
            if (markTerminal) {
                terminalUnresolvedIds += id
            }
        }
        terminalUnresolvedIds.toSet()
    }

    /**
     * Clears [id] from [terminalUnresolvedIds] (if present) and queues it for a
     * fresh lookup, returning the updated unresolved snapshot and whether [id]
     * was actually queued (false if already resolved, queued, or in-flight).
     */
    fun retry(id: String): Pair<Boolean, Set<String>> = synchronized(lock) {
        val clean = id.trim()
        if (clean.isEmpty()) return false to terminalUnresolvedIds.toSet()
        if (clean in resolvedIds) return false to terminalUnresolvedIds.toSet()
        val activeInFlight = inFlightBySub.values.flatten().toSet()
        if (clean in queuedIds || clean in activeInFlight) {
            return false to terminalUnresolvedIds.toSet()
        }
        terminalUnresolvedIds -= clean
        queuedIds += clean
        true to terminalUnresolvedIds.toSet()
    }

    /**
     * Cancels [ids] that were queued before a subscription started.
     * When [markTerminal] is true (e.g. no relays available at all), unresolved
     * IDs become terminal; otherwise they are released so a retry can pick them up.
     */
    fun finishQueued(ids: Collection<String>, markTerminal: Boolean): Set<String> = synchronized(lock) {
        val stillCovered = inFlightBySub.values.flatten().toSet()
        for (id in ids) {
            queuedIds -= id
            if (markTerminal && id !in resolvedIds && id !in stillCovered) {
                terminalUnresolvedIds += id
            }
        }
        terminalUnresolvedIds.toSet()
    }

    fun isResolved(id: String): Boolean = synchronized(lock) { id in resolvedIds }

    fun snapshotUnresolved(): Set<String> = synchronized(lock) { terminalUnresolvedIds.toSet() }

    /** Clears active lookup tracking (e.g. on feed refresh or account switch). */
    fun reset(clearResolved: Boolean = false) = synchronized(lock) {
        if (clearResolved) resolvedIds.clear()
        queuedIds.clear()
        inFlightBySub.clear()
        terminalUnresolvedIds.clear()
    }
}
