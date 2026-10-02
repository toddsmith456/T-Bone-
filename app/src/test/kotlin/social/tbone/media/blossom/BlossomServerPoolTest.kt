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
    fun `a server outside the enabled pool is ignored`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://not-enabled.example.com",
            default = "https://also-not-enabled.example.com",
            shuffle = noShuffle,
        )
        assertEquals(pool, ordered)
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
