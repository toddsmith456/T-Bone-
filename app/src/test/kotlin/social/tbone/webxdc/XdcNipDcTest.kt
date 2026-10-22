package social.tbone.webxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-DC parsing, using the exact event shapes Ditto publishes:
 * kind 1063 with `url`, `m`, `x`, `alt`, `webxdc` and `thumb` tags.
 */
class XdcNipDcTest {

    private val baseTags = listOf(
        listOf("url", "https://blossom.ditto.pub/1c9e60c6b427119c5fbc91ad2af0413779d9af4ecc804c022f7af23e798bc005.bin.xdc"),
        listOf("m", "application/x-webxdc"),
        listOf("x", "a1b2c3d4e5f6"),
        listOf("alt", "Webxdc app: XDC-Chess"),
        listOf("webxdc", "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"),
        listOf("thumb", "https://blossom.ditto.pub/chess-icon.png"),
        listOf("size", "84213"),
        listOf("summary", "chess"),
    )

    @Test
    fun parsesKind1063LikeDitto() {
        val app = XdcNipDc.parseFileMetadata(
            kind = 1063,
            eventId = "e1",
            pubkey = "p1",
            createdAt = 1792533553,
            content = "Chess over Nostr. Play with friends!",
            tags = baseTags,
        )
        assertEquals("XDC-Chess", app!!.name)
        assertEquals("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d", app.uuid)
        assertEquals("https://blossom.ditto.pub/chess-icon.png", app.iconUrl)
        assertEquals(84213L, app.sizeBytes)
        assertEquals("Chess over Nostr. Play with friends!", app.description)
        assertTrue(app.url.endsWith(".xdc"))
    }

    @Test
    fun ignoresOtherMimeTypes() {
        val tags = baseTags.map { tag ->
            if (tag[0] == "m") listOf("m", "image/png") else tag
        }
        val app = XdcNipDc.parseFileMetadata(1063, "e", "p", 0, "", tags)
        assertNull(app)
    }

    @Test
    fun ignoresOtherKinds() {
        assertNull(XdcNipDc.parseFileMetadata(1, "e", "p", 0, "", baseTags))
    }

    @Test
    fun toleratesMissingMimeWhenUrlEndsInXdc() {
        val tags = baseTags.filterNot { it[0] == "m" }
        val app = XdcNipDc.parseFileMetadata(1063, "e", "p", 0, "", tags)
        assertEquals("XDC-Chess", app!!.name)
    }

    @Test
    fun ignoresNonHttpUrls() {
        val tags = baseTags.map { tag ->
            if (tag[0] == "url") listOf("url", "javascript:alert(1)") else tag
        }
        assertNull(XdcNipDc.parseFileMetadata(1063, "e", "p", 0, "", tags))
    }

    @Test
    fun nameFallsBackThroughSummaryThenFileName() {
        val noAlt = baseTags.filterNot { it[0] == "alt" }
        assertEquals(
            "chess",
            XdcNipDc.parseFileMetadata(1063, "e", "p", 0, "", noAlt)!!.name,
        )

        val noAltNoSummary = noAlt.filterNot { it[0] == "summary" }
        assertEquals(
            "1c9e60c6b427119c5fbc91ad2af0413779d9af4ecc804c022f7af23e798bc005.bin",
            XdcNipDc.parseFileMetadata(1063, "e", "p", 0, "", noAltNoSummary)!!.name,
        )
    }

    @Test
    fun parsesImetaAttachmentOnKind1() {
        val tags = listOf(
            listOf(
                "imeta",
                "url https://blossom.example.com/abc.xdc",
                "m application/x-webxdc",
                "x deadbeef",
                "webxdc 11111111-2222-3333-4444-555555555555",
            ),
        )
        val app = XdcNipDc.parseImeta("e2", "p2", 5, "try this", tags)
        assertEquals("abc", app!!.name)
        assertEquals("11111111-2222-3333-4444-555555555555", app.uuid)
        assertEquals("https://blossom.example.com/abc.xdc", app.url)
    }

    @Test
    fun imetaWithoutWebxdcMimeIsIgnored() {
        val tags = listOf(
            listOf("imeta", "url https://example.com/cat.png", "m image/png"),
        )
        assertNull(XdcNipDc.parseImeta("e", "p", 0, "", tags))
    }

    @Test
    fun storeCatalogUrlsAreBuiltFromTheExport() {
        val entry = XdcStoreEntry(
            appId = "nelson9608-calculator-xdc",
            name = "Calculator",
            cacheRelname = "nelson9608-calculator-xdc-V1.0.0.xdc",
            iconRelname = "nelson9608-calculator-xdc-icon.png",
        )
        assertEquals(
            "https://apps.testrun.org/nelson9608-calculator-xdc-V1.0.0.xdc",
            XdcStoreCatalog.downloadUrl(entry),
        )
        assertEquals(
            "https://apps.testrun.org/nelson9608-calculator-xdc-icon.png",
            XdcStoreCatalog.iconUrl(entry),
        )
    }
}
