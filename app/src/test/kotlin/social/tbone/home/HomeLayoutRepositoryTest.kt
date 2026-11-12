package social.tbone.home

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The repository's job is to never hand back an empty or broken home screen, so
 * these tests concentrate on the invariants and on the "absent means defaults"
 * rule that a previous release got wrong for notification filters.
 */
class HomeLayoutRepositoryTest {

    private lateinit var scope: CoroutineScope
    private lateinit var file: File
    private lateinit var store: DataStore<Preferences>
    private lateinit var repo: HomeLayoutRepository

    @Before fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = File.createTempFile("home_layout", ".preferences_pb").also { it.delete() }
        store = PreferenceDataStoreFactory.create(scope = scope) { file }
        repo = HomeLayoutRepository(store)
    }

    @After fun tearDown() { scope.cancel(); file.delete() }

    private fun awaitReady() = runBlocking {
        withTimeout(5_000) { while (!repo.loaded.value) delay(10) }
        repo.layout.value
    }

    @Test
    fun `a fresh install gets the four default tabs`() = runBlocking {
        val layout = repo.awaitLoaded()
        assertEquals(4, layout.slots.size)
        assertTrue(layout.slots[0].source is FeedSource.Follows)
        assertTrue(layout.slots[1].source is FeedSource.Clock)
        assertTrue(layout.slots[2].source is FeedSource.Suggested)
        assertTrue(layout.slots[3].source is FeedSource.Global)
    }

    @Test
    fun `an unreadable stored layout falls back to defaults`() = runBlocking {
        store.edit { it[stringPreferencesKey("home_layout_json")] = "garbage{" }
        val reloaded = HomeLayoutRepository(store)
        val layout = reloaded.awaitLoaded()
        assertEquals("a corrupt store must not blank the home screen", 4, layout.slots.size)
    }

    @Test
    fun `added slots persist across a new instance`() = runBlocking {
        val added = repo.add(FeedSource.Hashtag(tags = listOf("bitcoin")))
        assertNotNull(added)

        // A second repository over the same file is the closest thing to a restart.
        val restarted = HomeLayoutRepository(store)
        val layout = restarted.awaitLoaded()
        assertEquals(5, layout.slots.size)
        assertTrue(layout.slots.last().source is FeedSource.Hashtag)
        assertEquals(added!!.id, layout.slots.last().id)
    }

    @Test
    fun `the follow slot cannot be removed`() = runBlocking {
        val layout = repo.awaitLoaded()
        val followSlot = layout.slots.first()
        repo.remove(followSlot.id)
        val after = repo.layout.value
        assertTrue(after.slots.any { it.source is FeedSource.Follows })
        assertEquals(4, after.slots.size)
    }

    @Test
    fun `the follow slot cannot be hidden`() = runBlocking {
        val followSlot = repo.awaitLoaded().slots.first()
        repo.setVisible(followSlot.id, false)
        assertTrue(repo.layout.value.slots.first().visible)
    }

    @Test
    fun `hiding a slot keeps it in the layout so it can come back`() = runBlocking {
        val clockSlot = repo.awaitLoaded().slots[1]
        repo.setVisible(clockSlot.id, false)
        val after = repo.layout.value
        assertEquals("the slot must not be deleted", 4, after.slots.size)
        assertEquals(3, after.visibleSlots.size)
        assertFalse(after.slots[1].visible)
    }

    @Test
    fun `reordering keeps the follow slot first`() = runBlocking {
        val layout = repo.awaitLoaded()
        val ids = layout.slots.map { it.id }
        // Try to move global to the very front.
        repo.reorder(listOf(ids[3], ids[0], ids[1], ids[2]))
        val after = repo.layout.value
        assertTrue("slot 1 is pinned to follows", after.slots[0].source is FeedSource.Follows)
        assertTrue(after.slots[1].source is FeedSource.Global)
    }

    @Test
    fun `reordering does not lose slots that were not mentioned`() = runBlocking {
        val layout = repo.awaitLoaded()
        val ids = layout.slots.map { it.id }
        repo.reorder(listOf(ids[2], ids[1])) // global (3) not mentioned
        val after = repo.layout.value
        assertEquals(4, after.slots.size)
        assertTrue(after.slots.any { it.source is FeedSource.Global })
    }

    @Test
    fun `slot count is capped`() = runBlocking {
        repeat(HomeLayout.MAX_SLOTS + 3) { index ->
            repo.add(FeedSource.Hashtag(tags = listOf("tag$index")))
        }
        assertEquals(HomeLayout.MAX_SLOTS, repo.layout.value.slots.size)
    }

    @Test
    fun `two feeds of the same type keep distinct identities`() = runBlocking {
        val hour = repo.add(FeedSource.Clock(hours = 1))
        val day = repo.add(FeedSource.Clock(hours = 24))
        assertNotNull(hour)
        assertNotNull(day)
        assertTrue(hour!!.id != day!!.id)
        val clocks = repo.layout.value.slots.filter { it.source is FeedSource.Clock }
        assertEquals(listOf(6, 1, 24), clocks.map { (it.source as FeedSource.Clock).hours })
    }

    @Test
    fun `upsert updates in place without changing the id`() = runBlocking {
        val day = repo.add(FeedSource.Clock(hours = 24))!!
        repo.upsert(day.copy(source = FeedSource.Clock(hours = 2)))
        val stored = repo.layout.value.slot(day.id)
        assertNotNull(stored)
        assertEquals(2, (stored!!.source as FeedSource.Clock).hours)
    }

    @Test
    fun `export and import round-trip the layout`() = runBlocking {
        repo.add(FeedSource.People(name = "devs", pubkeys = listOf("a".repeat(64))))
        val json = repo.exportJson()

        repo.resetToDefaults()
        assertEquals(4, repo.layout.value.slots.size)

        val result = repo.importJson(json)
        assertTrue(result.isSuccess)
        assertEquals(5, repo.layout.value.slots.size)
    }

    @Test
    fun `importing junk fails cleanly and changes nothing`() = runBlocking {
        val before = repo.exportJson()
        val result = repo.importJson("""{"nope":true}""")
        assertTrue(result.isFailure)
        assertEquals(before, repo.exportJson())
    }

    @Test
    fun `reset brings back exactly the shipped tabs`() = runBlocking {
        repo.add(FeedSource.Hashtag(tags = listOf("x")))
        repo.resetToDefaults()
        val after = repo.layout.value
        assertEquals(4, after.slots.size)
        assertTrue(after.slots[2].source is FeedSource.Suggested)
    }

    @Test
    fun `the landing slot defaults to the first visible one`() {
        val layout = HomeLayout.default()
        assertEquals(layout.slots.first().id, layout.landingSlot?.id)
        assertNull(layout.lastActiveSlotId)
    }

    @Test
    fun `loaded becomes true after the first read`() {
        val layout = awaitReady()
        assertTrue(repo.loaded.value)
        assertEquals(4, layout.slots.size)
    }
}
