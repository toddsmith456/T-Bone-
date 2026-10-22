package social.tbone.webxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.EventKind
import social.tbone.nostr.Filter

/**
 * The two Nostr feeds are only as good as the wire filter, and the toolbox
 * promotion is only as good as the persisted JSON. Both are checked here.
 */
class XdcFilterAndToolsTest {

    private val tbonePubkey = "1194bf8851a0ab5fe215f223b44970f68aed99b220b88c7a414dfbe30f7e5078"

    @Test
    fun tboneFeedSerialisesHashMAndAuthor() {
        val json = Filter(
            kinds = listOf(EventKind.FILE_METADATA),
            mTags = listOf(XdcNipDc.MIME),
            authors = listOf(tbonePubkey),
            limit = 100,
        ).toJsonObject()

        // What actually goes on the wire to the relay.
        assertTrue(json.toString().contains("\"#m\":[\"application/x-webxdc\"]"))
        assertTrue(json.toString().contains("\"kinds\":[1063]"))
        assertTrue(json.toString().contains(tbonePubkey))
        assertEquals(listOf(tbonePubkey), (json["authors"] as kotlinx.serialization.json.JsonArray)
            .map { (it as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun worldFeedOmitsAuthorsButKeepsHashM() {
        val json = Filter(
            kinds = listOf(EventKind.FILE_METADATA),
            mTags = listOf(XdcNipDc.MIME),
            limit = 100,
        ).toJsonObject()

        assertFalse("world feed must not be restricted to one author", json.containsKey("authors"))
        assertTrue(json.containsKey("#m"))
    }

    @Test
    fun nullTagsAreDroppedFromTheWirePayload() {
        val json = Filter(kinds = listOf(1)).toJsonObject()
        assertFalse(json.containsKey("#m"))
        assertFalse(json.containsKey("authors"))
    }

    @Test
    fun customToolsRoundTrip() {
        val tools = listOf(
            XdcCustomTool(appId = "abc", name = "chess club", iconId = "game", addedAt = 42L),
            XdcCustomTool(appId = "def", name = "groceries ☑", iconId = "checklist", addedAt = 43L),
        )
        assertEquals(tools, XdcCustomToolsCodec.decode(XdcCustomToolsCodec.encode(tools)))
    }

    @Test
    fun customToolsTolerateGarbage() {
        assertTrue(XdcCustomToolsCodec.decode("not json").isEmpty())
        assertTrue(XdcCustomToolsCodec.decode(null).isEmpty())
        assertTrue(XdcCustomToolsCodec.decode("").isEmpty())
    }
}
