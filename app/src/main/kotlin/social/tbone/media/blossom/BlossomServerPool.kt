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
     * A [preferred] server is honoured **even when it is not in [pool]**. That
     * case is the bug this guarded against: a server could be in the compose
     * chooser (because the account published it) while being absent from the
     * locally enabled set, and tapping it silently did nothing — the upload went
     * to some other server. An explicit pick is a user instruction, so it wins.
     *
     * [shuffle] is injectable so tests get deterministic order.
     */
    fun orderedCandidates(
        pool: List<String>,
        preferred: String?,
        default: String?,
        shuffle: (List<String>) -> List<String> = { it.shuffled() },
    ): List<String> {
        val ordered = LinkedHashSet<String>()
        preferred?.takeIf { it.isNotBlank() }?.let { ordered += it }
        default?.let { if (it in pool) ordered += it }
        ordered += shuffle(pool)
        // Nothing enabled and nothing explicitly picked: fail closed rather than
        // inventing a server. An explicit pick alone is still enough to upload.
        return ordered.toList()
    }
}
