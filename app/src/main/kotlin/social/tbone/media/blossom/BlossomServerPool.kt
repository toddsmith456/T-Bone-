package social.tbone.media.blossom

/**
 * Pure server-selection policy for uploads — kept free of Android/OkHttp types
 * so the exact ordering rules can be unit-tested.
 */
object BlossomServerPool {

    /**
     * Orders the enabled [pool] into the list of servers an upload may be tried
     * against, best candidate first:
     *
     *  1. [preferred] — the server picked on the compose screen,
     *  2. [default]   — the user's default server from Settings → Blossom,
     *  3. every remaining pool member (shuffled, so "random" stays random).
     *
     * Entries that are not in [pool] are ignored, and duplicates are collapsed:
     * a server can never be tried twice in one upload.
     *
     * [shuffle] is injectable so tests get deterministic order.
     */
    fun orderedCandidates(
        pool: List<String>,
        preferred: String?,
        default: String?,
        shuffle: (List<String>) -> List<String> = { it.shuffled() },
    ): List<String> {
        if (pool.isEmpty()) return emptyList()
        val ordered = LinkedHashSet<String>()
        preferred?.let { if (it in pool) ordered += it }
        default?.let { if (it in pool) ordered += it }
        ordered += shuffle(pool)
        return ordered.toList()
    }
}
