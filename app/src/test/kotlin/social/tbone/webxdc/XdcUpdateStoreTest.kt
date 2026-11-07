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
    fun oversizedAppendDoesNotChangeJournal() {
        val file = tmp.newFile("quota.log")
        val store = XdcUpdateStore(file)
        store.append("small")
        val before = file.readBytes()
        try {
            store.append("a".repeat(XdcUpdateStore.MAX_BYTES.toInt()))
            throw AssertionError("expected quota rejection")
        } catch (_: IllegalStateException) { }
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals("small", store.load().single().payloadJson)
    }

    @Test
    fun nullPayloadIsStoredAsLiteralNull() {
        val store = XdcUpdateStore(tmp.newFile("null.log"))
        store.append("null")
        assertEquals("null", store.load().single().payloadJson)
    }

    @Test fun concurrentInstancesShareSerialAndQuotaLock() {
        val file = tmp.newFile("concurrent.log")
        val stores = listOf(XdcUpdateStore(file), XdcUpdateStore(file))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val tasks = (0 until 48).map { index ->
                executor.submit<Int> {
                    start.await()
                    stores[index % 2].append("{\"n\":$index}").serial
                }
            }
            start.countDown()
            val serials = tasks.map { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }.sorted()
            assertEquals((1..48).toList(), serials)
            assertEquals((1..48).toList(), stores[0].load().map { it.serial })
            assertTrue(file.length() <= XdcUpdateStore.MAX_BYTES)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun oversizeInputIsRejectedBeforeChangingJournal() {
        val file = tmp.newFile("input-limit.log")
        val store = XdcUpdateStore(file)
        store.append("1")
        val original = file.readBytes()
        try {
            store.append("x".repeat(XdcUpdateStore.MAX_BYTES.toInt() + 1))
            throw AssertionError("expected size rejection")
        } catch (_: IllegalArgumentException) { }
        assertTrue(original.contentEquals(file.readBytes()))
    }


    @Test fun staleJournalInstanceCannotRecreateUpdatesAfterWipe() = kotlinx.coroutines.runBlocking {
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val file = tmp.newFile("wipe.log")
        try {
            val coordinator = social.tbone.notes.NoteWriteCoordinator(owner)
            val oldStore = XdcUpdateStore(file, coordinator)
            oldStore.append("1")
            assertTrue(coordinator.runWipe(social.tbone.notes.NoteWriteCoordinator.WipeKind.APP) {
                coordinator.pauseAndDrain()
                file.delete()
                true
            })
            try { oldStore.append("2"); throw AssertionError("stale store wrote after wipe") }
            catch (_: java.io.IOException) { }
            assertTrue(!file.exists())
            assertEquals(1, XdcUpdateStore(file, coordinator).append("3").serial)
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

}
