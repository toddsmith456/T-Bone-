package social.tbone.media.blossom

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Server-selection rules for Blossom uploads:
 *  - explicit Write-tab pick → hard override (ONLY that server, no silent failover);
 *  - no explicit pick → Settings default (if enabled), then remaining pool for failover.
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
    fun `explicit choice is a strict override with no silent failover to Settings pool`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://blossom.ditto.pub",
            default = "https://blossom.data.haus",
            shuffle = noShuffle,
        )
        assertEquals(listOf("https://blossom.ditto.pub"), ordered)
    }

    @Test
    fun `explicit choice trims whitespace and trailing slashes`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "  https://blossom.ditto.pub/  ",
            default = "https://nostr.download",
            shuffle = noShuffle,
        )
        assertEquals(listOf("https://blossom.ditto.pub"), ordered)
    }

    @Test
    fun `with no explicit choice the default is tried first`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = null,
            default = "https://blossom.data.haus",
            shuffle = noShuffle,
        )
        assertEquals(
            listOf("https://blossom.data.haus", "https://nostr.download", "https://blossom.ditto.pub"),
            ordered,
        )
    }

    @Test
    fun `blank preferred falls back to Settings rules`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "   ",
            default = "https://blossom.data.haus",
            shuffle = noShuffle,
        )
        assertEquals("https://blossom.data.haus", ordered.first())
        assertEquals(pool.size, ordered.size)
    }

    @Test
    fun `with no choice at all the whole pool is still available for failover`() {
        val ordered = BlossomServerPool.orderedCandidates(pool, null, null, noShuffle)
        assertEquals(pool.size, ordered.size)
        assertEquals(pool.toSet(), ordered.toSet())
    }

    @Test
    fun `an explicitly chosen server is honoured exclusively even when outside the pool`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = "https://added.example.com",
            default = "https://nostr.download",
            shuffle = noShuffle,
        )
        assertEquals(listOf("https://added.example.com"), ordered)
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
    fun `a server is never tried twice in Settings failover mode`() {
        val ordered = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = null,
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
