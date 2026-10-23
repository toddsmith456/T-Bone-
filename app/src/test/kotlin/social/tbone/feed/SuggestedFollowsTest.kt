package social.tbone.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The suggested feed's roster is shipped as a text asset, so these tests guard
 * the two things that can silently break it: the parser drifting, and the asset
 * being regenerated with the wrong number of keys.
 */
class SuggestedFollowsTest {

    private val hex = "0123456789abcdef"

    private fun key(seed: Char): String = seed.toString().repeat(64)

    @Test
    fun `parser keeps only 64-char hex, drops comments and dupes`() {
        val text = buildString {
            appendLine("# a comment")
            appendLine()
            appendLine(key('a'))
            appendLine("  ${key('b')}  ")
            appendLine(key('A'))          // uppercased dupe of the first key
            appendLine("${key('c')} # trailing note")
            appendLine("not-a-key")
            appendLine(key('d').dropLast(1))   // 63 chars
            appendLine("${key('e')}0")         // 65 chars
        }
        assertEquals(listOf(key('a'), key('b'), key('c')), SuggestedFollows.parse(text))
    }

    @Test
    fun `asset holds the full curated list`() {
        val asset = File("src/main/assets/tbone-suggested-follows.txt")
        assertTrue("asset missing at ${asset.absolutePath}", asset.exists())
        val keys = SuggestedFollows.parse(asset.readText())
        assertEquals(1346, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.length == 64 && it.all { c -> c in hex } })
    }

    @Test
    fun `chunking covers every author exactly once`() {
        val keys = SuggestedFollows.parse(File("src/main/assets/tbone-suggested-follows.txt").readText())
        val chunks = SuggestedFollows.chunk(keys)
        assertEquals(9, chunks.size)
        assertTrue(chunks.all { it.size <= SuggestedFollows.CHUNK_SIZE })
        assertEquals(keys, chunks.flatten())
        assertEquals(keys.toSet(), chunks.flatten().toSet())
        // A REQ body stays well under the ~90 kB that relays choke on.
        assertTrue(chunks.all { chunk -> chunk.sumOf { it.length + 12 } < 12_000 })
    }
}
