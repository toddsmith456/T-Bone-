package social.tbone.webxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XdcArchiveTest {

    private fun zip(entries: Map<String, ByteArray>, method: Int = ZipEntry.DEFLATED): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name).apply {
                    this.method = method
                    if (method == ZipEntry.STORED) {
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sample(manifest: String? = null, icon: Boolean = true): ByteArray {
        val entries = linkedMapOf<String, ByteArray>(
            "index.html" to "<!DOCTYPE html><html><head><title>x</title></head><body>hi</body></html>".toByteArray(),
            "app.js" to "console.log('hello')".toByteArray(),
            "sub/data.txt" to "nested".toByteArray(),
        )
        if (manifest != null) entries["manifest.toml"] = manifest.toByteArray()
        if (icon) entries["icon.png"] = byteArrayOf(1, 2, 3, 4)
        return zip(entries)
    }

    @Test
    fun readsManifestNameAndIcon() {
        val pkg = XdcArchive.read(
            sample(
                """
                # webxdc manifest
                name = "Chess Deluxe"
                source_code_url = "https://example.org/chess"
                description = "Play chess with the family"
                """.trimIndent(),
            ),
        )
        assertEquals("Chess Deluxe", pkg.name)
        assertEquals("https://example.org/chess", pkg.sourceCodeUrl)
        assertEquals("Play chess with the family", pkg.description)
        assertEquals("icon.png", pkg.iconPath)
        assertNotNull(pkg.files["index.html"])
        assertNotNull(pkg.files["sub/data.txt"])
        assertEquals(32, pkg.id.length)
        assertTrue(pkg.sizeBytes > 0)
    }

    @Test
    fun fallsBackToFileNameWithoutManifest() {
        val pkg = XdcArchive.read(sample(icon = false), fallbackName = "Oregon-Trail.xdc")
        assertEquals("Oregon-Trail", pkg.name)
        assertNull(pkg.iconPath)
    }

    @Test
    fun acceptsStoredZipEntries() {
        val pkg = XdcArchive.read(zip(mapOf("index.html" to "<html></html>".toByteArray()), ZipEntry.STORED))
        assertEquals("webxdc app", pkg.name)
    }

    @Test
    fun rejectsArchiveWithoutIndexHtml() {
        val bytes = zip(mapOf("main.html" to "<html></html>".toByteArray()))
        try {
            XdcArchive.read(bytes)
            fail("expected XdcException")
        } catch (e: XdcException) {
            assertTrue(e.message!!.contains("index.html"))
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val bytes = zip(
            mapOf(
                "index.html" to "<html></html>".toByteArray(),
                "../escape.sh" to "boom".toByteArray(),
            ),
        )
        try {
            XdcArchive.read(bytes)
            fail("expected XdcException")
        } catch (e: XdcException) {
            assertTrue(e.message!!.contains("unsafe path"))
        }
    }

    @Test
    fun absolutePathsAreSandboxedNotEscaped() {
        val bytes = zip(
            mapOf(
                "index.html" to "<html></html>".toByteArray(),
                "/etc/passwd" to "root".toByteArray(),
            ),
        )
        val pkg = XdcArchive.read(bytes)
        // The leading slash is dropped: the file lands inside the app's private dir.
        assertEquals("root", String(pkg.files["etc/passwd"]!!))
        assertNull(pkg.files["/etc/passwd"])
    }

    @Test
    fun rejectsNonZipBytes() {
        try {
            XdcArchive.read("this is not a zip".toByteArray())
            fail("expected XdcException")
        } catch (e: XdcException) {
            assertTrue(e.message!!.contains("zip"))
        }
    }

    @Test
    fun identifierIsStableAndContentAddressed() {
        val a = XdcArchive.read(sample())
        val b = XdcArchive.read(sample())
        assertEquals(a.id, b.id)
        val c = XdcArchive.read(sample(manifest = "name = \"other\""))
        assertFalse(a.id == c.id)
    }

    @Test
    fun manifestHandlesEscapesSingleQuotesAndComments() {
        val manifest = XdcArchive.parseManifest(
            """
            name = 'Chess Deluxe'
            source_code_url = "https://example.org/a?b=1#frag"  # trailing comment
            description = "line one\nline two"
            ignored = 42
            """.trimIndent(),
        )
        assertEquals("Chess Deluxe", manifest.name)
        assertEquals("https://example.org/a?b=1#frag", manifest.sourceCodeUrl)
        assertEquals("line one\nline two", manifest.description)
    }

    @Test
    fun normalisePathRejectsTraversalAndDrives() {
        assertNull(XdcArchive.normalisePath("../x"))
        assertNull(XdcArchive.normalisePath("a/../../b"))
        assertNull(XdcArchive.normalisePath("C:/windows/system32"))
        assertEquals("a/b.txt", XdcArchive.normalisePath("/a//b.txt"))
        assertEquals("index.html", XdcArchive.normalisePath("./index.html"))
    }

    @Test
    fun looksLikeXdcAcceptsRealShape() {
        assertTrue(XdcArchive.looksLikeXdc(sample()))
        assertFalse(XdcArchive.looksLikeXdc(byteArrayOf(0, 1, 2)))
    }
}
