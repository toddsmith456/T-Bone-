package social.tbone.media.blossom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Descriptor parsing is deliberately forgiving: the URL we hand back to the
 * user comes from here, so an unexpected server response must not throw away a
 * successful upload.
 */
class BlossomUploadResultTest {

    @Test
    fun `parses the descriptor nostr download actually returned`() {
        // Captured live: PUT https://nostr.download/upload -> HTTP 201
        val body = """
            {"url":"https://nostr.download/85d4405a6ac4cc2c6236fa376550e0912510284e7a7dd95ea7a10486cd2535bb.png",
             "sha256":"85d4405a6ac4cc2c6236fa376550e0912510284e7a7dd95ea7a10486cd2535bb",
             "size":78,"type":"image/png","uploaded":1790919456,
             "nip94":[["url","https://nostr.download/85d4.png"],["x","85d4"]]}
        """.trimIndent()

        val result = BlossomJson.parse(body)!!
        assertEquals(
            "https://nostr.download/85d4405a6ac4cc2c6236fa376550e0912510284e7a7dd95ea7a10486cd2535bb.png",
            result.url,
        )
        assertEquals("85d4405a6ac4cc2c6236fa376550e0912510284e7a7dd95ea7a10486cd2535bb", result.sha256)
        assertEquals(78L, result.size)
        assertEquals("image/png", result.type)
    }

    @Test
    fun `unknown fields such as nip94 or ox do not break parsing`() {
        val body = """{"url":"https://x.example.com/ab.png","sha256":"ab","ox":"cd","brandNew":42}"""
        assertEquals("https://x.example.com/ab.png", BlossomJson.parse(body)!!.url)
    }

    @Test
    fun `accepts a bare quoted url`() {
        assertEquals(
            "https://x.example.com/ab",
            BlossomJson.parse("\"https://x.example.com/ab\"")!!.url,
        )
    }

    @Test
    fun `empty or garbage bodies yield null so the caller can fall back`() {
        assertNull(BlossomJson.parse(null))
        assertNull(BlossomJson.parse(""))
        assertNull(BlossomJson.parse("   "))
        assertNull(BlossomJson.parse("not json at all"))
        assertNull(BlossomJson.parse("{ truncated"))
    }

    @Test
    fun `a descriptor without a url parses but leaves url null`() {
        val result = BlossomJson.parse("""{"sha256":"ab","size":5}""")!!
        assertNull(result.url)
        assertEquals("ab", result.sha256)
        assertTrue(result.size == 5L)
    }
}
