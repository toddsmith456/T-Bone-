package social.tbone.media.blossom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The selection rules users see: pick on compose → that server; otherwise the
 * default; otherwise random. The difference from before is that *every*
 * remaining server is now a failover candidate, not just one.
 */
class BlossomServerPoolTest {

    private val pool = listOf(
        "https://nostr.download",
        "https://blossom.data.haus",
        "https://blossom.ditto.pub",
    )

    /** Deterministic stand-in for shuffle. */
    private val noShuffle: (List<String>) -> List<String> = { it }

    @Test
    fun `explicit choice wins, then default, then every other server`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://blossom.ditto.pub",
            default = "https://blossom.data.haus",
            shuffle = noShuffle,
        )
        assertEquals(
            listOf("https://blossom.ditto.pub", "https://blossom.data.haus", "https://nostr.download"),
            ordered,
        )
    }

    @Test
    fun `with no explicit choice the default is tried first`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = null,
            default = "https://blossom.data.haus",
            shuffle = noShuffle,
        )
        assertEquals("https://blossom.data.haus", ordered.first())
    }

    @Test
    fun `with no choice at all the whole pool is still available for failover`() {
        val ordered = BlossomServerPool.orderedCandidates(pool, null, null, noShuffle)
        assertEquals(pool.size, ordered.size)
        assertEquals(pool.toSet(), ordered.toSet())
    }

    @Test
    fun `an explicitly chosen server is honoured even when it is outside the pool`() {
        // This used to assert the opposite, and that assertion was the bug: a
        // server could be offered in the compose chooser (because the account
        // published it) while missing from the local enabled set, so tapping it
        // silently uploaded somewhere else. An explicit pick is a user
        // instruction, so it is tried first regardless of the pool.
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://added.example.com",
            default = null,
            shuffle = noShuffle,
        )
        assertEquals("https://added.example.com", ordered.first())
        assertEquals(pool + "https://added.example.com", ordered.distinct())
    }

    @Test
    fun `the default is still constrained to the pool`() {
        // Only `preferred` escapes the pool. A default that is not enabled must
        // not silently send uploads to a server the user turned off.
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = null,
            default = "https://also-not-enabled.example.com",
            shuffle = noShuffle,
        )
        assertEquals(pool, ordered)
    }

    @Test
    fun `an explicit pick alone is enough to upload`() {
        // Nothing enabled but a server picked on the compose screen: the pick
        // must survive rather than being dropped with the empty pool.
        val ordered = BlossomServerPool.orderedCandidates(
            pool = emptyList(),
            preferred = "https://added.example.com",
            default = null,
            shuffle = noShuffle,
        )
        assertEquals(listOf("https://added.example.com"), ordered)
    }

    @Test
    fun `a server is never tried twice`() {
        // preferred == default == first pool member
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://nostr.download",
            default = "https://nostr.download",
            shuffle = noShuffle,
        )
        assertEquals(pool, ordered)
        assertEquals(ordered.size, ordered.distinct().size)
    }

    @Test
    fun `an empty pool yields no candidates`() {
        assertEquals(emptyList<String>(), BlossomServerPool.orderedCandidates(emptyList(), null, null))
    }
}
