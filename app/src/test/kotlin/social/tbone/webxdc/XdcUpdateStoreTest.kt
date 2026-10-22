package social.tbone.webxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XdcUpdateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun serialsStartAtOneAndIncrease() {
        val store = XdcUpdateStore(tmp.newFile("updates.log"))
        val first = store.append("""{"count":1}""")
        val second = store.append("""{"count":2}""", info = "count 2")
        assertEquals(1, first.serial)
        assertEquals(2, second.serial)
    }

    @Test
    fun reloadFromDiskKeepsOrderAndPayloads() {
        val file = tmp.newFile("updates.log")
        val store = XdcUpdateStore(file)
        store.append("""{"turn":"x"}""", info = "x played", document = "game-1", summary = "1 move")
        store.append("""{"turn":"o"}""", href = "#board")

        val reloaded = XdcUpdateStore(file).load()
        assertEquals(2, reloaded.size)
        assertEquals("""{"turn":"x"}""", reloaded[0].payloadJson)
        assertEquals("x played", reloaded[0].info)
        assertEquals("game-1", reloaded[0].document)
        assertEquals("1 move", reloaded[0].summary)
        assertEquals("#board", reloaded[1].href)
        assertNull(reloaded[1].info)
    }

    @Test
    fun handlesUnicodeQuotesAndNewlinesInPayloadAndMetadata() {
        val store = XdcUpdateStore(tmp.newFile("unicode.log"))
        val payload = """{"text":"héllo — \"quoted\"\nsecond line 🎲"}"""
        store.append(payload, info = "üñïçødé · 日本語")
        val loaded = store.load().single()
        assertEquals(payload, loaded.payloadJson)
        assertEquals("üñïçødé · 日本語", loaded.info)
    }

    @Test
    fun emptyStoreLoadsEmptyList() {
        val store = XdcUpdateStore(tmp.newFile("empty.log"))
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun clearRemovesEverything() {
        val file = tmp.newFile("clear.log")
        val store = XdcUpdateStore(file)
        store.append("1")
        store.append("2")
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun nullPayloadIsStoredAsLiteralNull() {
        val store = XdcUpdateStore(tmp.newFile("null.log"))
        store.append("null")
        assertEquals("null", store.load().single().payloadJson)
    }
}
