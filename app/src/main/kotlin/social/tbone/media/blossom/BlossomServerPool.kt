package social.tbone.media.blossom

/**
 * Pure server-selection policy for uploads — kept free of Android/OkHttp types
 * so the exact ordering rules can be unit-tested.
 */
object BlossomServerPool {

    /**
     * Orders the list of servers an upload may be tried against:
     *
     *  - When [preferred] is explicitly chosen on the Write tab, it is a hard
     *    user override: **only** `[preferred]` is returned. Settings rules
     *    (Beaver Mode, default server, enabled pool) are bypassed and the
     *    upload will never silently fail over to a different server the user
     *    did not choose.
     *  - When [preferred] is null/blank (auto / Settings rules), [default]
     *    (if present in [pool]) is tried first, followed by every remaining
     *    member of [pool] (shuffled) for automatic failover.
     *
     * Entries that are not in [pool] are ignored for [default], and duplicates
     * are collapsed so a server can never be tried twice in one upload.
     *
     * [shuffle] is injectable so tests get deterministic order.
     */
    fun orderedCandidates(
        pool: List<String>,
        preferred: String?,
        default: String?,
        shuffle: (List<String>) -> List<String> = { it.shuffled() },
    ): List<String> {
        val explicit = preferred?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            return listOf(explicit)
        }
        val ordered = LinkedHashSet<String>()
        default?.let { if (it in pool) ordered += it }
        ordered += shuffle(pool)
        // Nothing enabled and nothing explicitly picked: fail closed rather than
        // inventing a server.
        return ordered.toList()
    }
}
