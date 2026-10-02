package social.tbone.media.blossom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlossomServerUrlTest {

    @Test
    fun `endpoints hang off the server root without doubling slashes`() {
        assertEquals("https://cdn.example.com/upload", BlossomServerUrl.upload("https://cdn.example.com"))
        assertEquals("https://cdn.example.com/upload", BlossomServerUrl.upload("https://cdn.example.com/"))
        assertEquals("https://cdn.example.com/media", BlossomServerUrl.media("https://cdn.example.com/"))
        assertEquals("https://cdn.example.com/mirror", BlossomServerUrl.mirror("https://cdn.example.com"))
        assertEquals(
            "https://cdn.example.com/list/ab12",
            BlossomServerUrl.list("https://cdn.example.com/", "ab12"),
        )
    }

    @Test
    fun `blob url carries the extension when one is known`() {
        val hash = "ABCD".repeat(16)
        assertEquals(
            "https://cdn.example.com/${hash.lowercase()}.jpg",
            BlossomServerUrl.blob("https://cdn.example.com", hash, "jpg"),
        )
        assertEquals(
            "https://cdn.example.com/${hash.lowercase()}",
            BlossomServerUrl.blob("https://cdn.example.com", hash),
        )
        // BUD-01: servers must accept the bare-hash form, and must not see a dot for a blank extension.
        assertEquals(
            "https://cdn.example.com/${hash.lowercase()}.webm",
            BlossomServerUrl.blob("https://cdn.example.com", hash, ".webm"),
        )
    }

    @Test
    fun `domain strips scheme port path and case`() {
        assertEquals("cdn.example.com", BlossomServerUrl.domain("https://cdn.example.com"))
        assertEquals("cdn.example.com", BlossomServerUrl.domain("https://CDN.Example.com"))
        assertEquals("cdn.example.com", BlossomServerUrl.domain("http://cdn.example.com:8080/path"))
        assertEquals("blossom.primal.net", BlossomServerUrl.domain("https://blossom.primal.net/"))
        assertEquals("", BlossomServerUrl.domain(""))
    }

    @Test
    fun `normalize accepts what users actually type`() {
        assertEquals("https://blossom.example.com", BlossomServerUrl.normalize("blossom.example.com"))
        assertEquals("https://blossom.example.com", BlossomServerUrl.normalize("  blossom.example.com/  "))
        assertEquals("https://blossom.example.com", BlossomServerUrl.normalize("https://blossom.example.com/"))
        assertEquals("http://blossom.example.com", BlossomServerUrl.normalize("http://blossom.example.com"))
        assertNull(BlossomServerUrl.normalize(""))
        assertNull(BlossomServerUrl.normalize("   "))
        assertNull("hostname without a dot is not a server", BlossomServerUrl.normalize("blossom"))
        assertNull("no spaces allowed", BlossomServerUrl.normalize("not a url"))
    }

    @Test
    fun `a normalized server survives the domain extraction round trip`() {
        val raw = "  MyServer.Example.COM:8443  "
        val normalized = BlossomServerUrl.normalize(raw)!!
        assertEquals("https://myserver.example.com:8443", normalized)
        assertEquals("myserver.example.com", BlossomServerUrl.domain(normalized))
    }
}
