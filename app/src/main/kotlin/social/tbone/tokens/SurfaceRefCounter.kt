package social.tbone.tokens

/**
 * Reference-counts "is this surface on screen" across screens.
 *
 * Steak data used to be fetched for the whole process lifetime, which meant four
 * REQs on every relay from the moment the app started. It is now fetched only
 * while something is showing it — but the same sheet can be opened from the
 * profile screen and the leaderboard, and a plain boolean would switch the
 * subscription off when the first of them closed.
 *
 * Pulled out of [SteakRepository] so the transitions are testable without a
 * relay pool: the interesting cases are all "did this edge fire?".
 */
internal class SurfaceRefCounter {

    private val lock = Any()
    private var holders = 0

    /**
     * Registers a holder. Returns true only on the 0 -> 1 edge, i.e. when the
     * caller must start the underlying work.
     */
    fun acquire(): Boolean = synchronized(lock) {
        holders += 1
        holders == 1
    }

    /**
     * Releases a holder, ignoring extra releases (a double `onDispose` must not
     * push the count negative and strand the subscription).
     * Returns true only on the 1 -> 0 edge.
     */
    fun release(): Boolean = synchronized(lock) {
        if (holders == 0) return false
        holders -= 1
        holders == 0
    }

    /** Transfer from a previous account: an open surface stays open. */
    fun isHeld(): Boolean = synchronized(lock) { holders > 0 }

    fun holders(): Int = synchronized(lock) { holders }

    fun reset() = synchronized(lock) { holders = 0 }
}
