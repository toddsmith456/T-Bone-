package social.tbone.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.Filter

/**
 * Subscription-ownership rules for the home slots.
 *
 * These are the failures that are invisible in manual testing: a leaked REQ
 * keeps a feed alive after you leave it, two slots talking to one subscription
 * starve each other, and a stale slot shows the wrong account's people. All
 * three are cheap to assert here and expensive to find on a device.
 */
class FeedSlotEngineTest {

    private fun query(limit: Int = 100) = FeedPlan.Query(
        filters = listOf(Filter(kinds = listOf(1), limit = limit)),
        pool = PoolChoice.PERSONAL,
        canPageOlder = true,
    )

    private fun layout(vararg sources: FeedSource): HomeLayout =
        HomeLayout(slots = sources.map { FeedSlot.of(it) })

    private fun engineWith(vararg sources: FeedSource): Pair<FeedSlotEngine, HomeLayout> {
        val engine = FeedSlotEngine()
        val home = layout(*sources)
        engine.sync(home)
        return engine to home
    }

    // ── Activation ───────────────────────────────────────────────────────────

    @Test
    fun `activating a slot loads it and reports the teardown of the previous one`() {
        val (engine, home) = engineWith(FeedSource.Follows(), FeedSource.Clock())
        val first = home.slots[0]
        val second = home.slots[1]

        engine.activate(first.id, query())
        engine.registerSub(first.id, "sub-a")
        engine.markSettled(first.id)

        val change = engine.activate(second.id, query())
        assertTrue(change is SlotChange.Switch)
        assertEquals(listOf("sub-a"), (change as SlotChange.Switch).teardown)
        assertEquals(second.id, change.load.slotId)
    }

    @Test
    fun `re-activating the active slot is a no-op once it has settled`() {
        val (engine, home) = engineWith(FeedSource.Follows())
        val slot = home.slots[0]
        engine.activate(slot.id, query())
        engine.markSettled(slot.id)

        assertEquals(SlotChange.None, engine.activate(slot.id, query()))
    }

    @Test
    fun `re-activating an unsettled slot reloads it`() {
        val (engine, home) = engineWith(FeedSource.Follows())
        val slot = home.slots[0]
        engine.activate(slot.id, query())
        // Not settled: the first exchange may have failed or still be in flight.
        assertTrue(engine.activate(slot.id, query()) is SlotChange.Reload)
    }

    @Test
    fun `a blocked slot still becomes active so its message can be shown`() {
        val (engine, home) = engineWith(FeedSource.People(name = "nobody", pubkeys = listOf("a".repeat(64))))
        val slot = home.slots[0]
        val change = engine.activate(slot.id, FeedPlan.Blocked("no people"))
        assertTrue(change is SlotChange.Blocked)
        assertEquals("no people", (change as SlotChange.Blocked).reason)
        assertEquals(slot.id, engine.activeSlotId)
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    @Test
    fun `switching tears down the previous slot's subscriptions`() {
        val (engine, home) = engineWith(FeedSource.Follows(), FeedSource.Clock(), FeedSource.Global())
        val a = home.slots[0]
        val b = home.slots[1]
        val c = home.slots[2]

        engine.activate(a.id, query())
        engine.registerSub(a.id, "sub-a")
        engine.activate(b.id, query())
        engine.registerSub(b.id, "sub-b")

        // Activating b already dropped a's subscription: only the active slot
        // is allowed to keep a REQ open.
        assertTrue("the previous slot must not keep its REQ", engine.runtime(a.id)!!.subIds.isEmpty())

        val change = engine.activate(c.id, query())
        assertTrue(change is SlotChange.Switch)
        // The ids to unsubscribe travel in `teardown`, which is what the caller
        // hands to `unsub` — that is the contract worth pinning.
        assertEquals(listOf("sub-b"), (change as SlotChange.Switch).teardown)
        assertTrue(engine.runtime(b.id)!!.subIds.isEmpty())
    }

    @Test
    fun `a slot that lost focus has no subscriptions left`() {
        val (engine, home) = engineWith(FeedSource.Clock(hours = 1), FeedSource.Clock(hours = 24))
        val hour = home.slots[0]
        val day = home.slots[1]

        engine.activate(hour.id, query())
        engine.registerSub(hour.id, "clock-1h")
        assertEquals(hour.id, engine.slotForSub("clock-1h")?.slotId)

        engine.activate(day.id, query())
        engine.registerSub(day.id, "clock-24h")

        assertEquals(day.id, engine.slotForSub("clock-24h")?.slotId)
        assertNull("the 1h slot must have been detached", engine.slotForSub("clock-1h"))
        assertNull(engine.slotForSub("nothing"))
    }

    @Test
    fun `the engine detects a subscription shared by two slots`() {
        val (engine, home) = engineWith(FeedSource.Clock(hours = 1), FeedSource.Clock(hours = 24))
        val hour = home.slots[0]
        val day = home.slots[1]
        engine.activate(hour.id, query())

        // Simulate the bug the guard exists for: an id copied into a second slot
        // by a careless refactor. It must fail loudly rather than let one feed
        // silently starve.
        engine.registerSub(hour.id, "shared")
        engine.registerSub(day.id, "shared")

        val error = runCatching {
            engine.runtime(day.id)!!.assertExclusive(engine.all())
        }.exceptionOrNull()
        assertNotNull("a shared subscription id must be rejected", error)
        assertTrue(error!!.message.orEmpty().contains("shares subscription ids"))
    }

    // ── Two slots of the same kind ───────────────────────────────────────────

    @Test
    fun `two clock feeds keep separate events and anchors`() {
        val (engine, home) = engineWith(FeedSource.Clock(hours = 1), FeedSource.Clock(hours = 24))
        val hour = home.slots[0]
        val day = home.slots[1]

        engine.activate(hour.id, query())
        engine.markLoaded(hour.id, "me", anchor = 1000L)
        engine.markSettled(hour.id)
        engine.runtime(hour.id)!!.events.add(note("a", 900))

        engine.activate(day.id, query())
        engine.markLoaded(day.id, "me", anchor = 500L)
        engine.markSettled(day.id)
        engine.runtime(day.id)!!.events.add(note("b", 400))

        assertEquals(1000L, engine.runtime(hour.id)!!.anchor)
        assertEquals(500L, engine.runtime(day.id)!!.anchor)
        assertEquals(listOf("a"), engine.runtime(hour.id)!!.events.map { it.id })
        assertEquals(listOf("b"), engine.runtime(day.id)!!.events.map { it.id })
    }

    @Test
    fun `changing a slot's source clears its cached events`() {
        val engine = FeedSlotEngine()
        val slot = FeedSlot.of(FeedSource.Clock(hours = 1))
        engine.sync(HomeLayout(slots = listOf(slot)))
        val runtime = engine.runtime(slot.id)!!
        runtime.events.add(note("stale", 1))

        engine.sync(HomeLayout(slots = listOf(slot.copy(source = FeedSource.Clock(hours = 24)))))
        val after = engine.runtime(slot.id)!!
        assertTrue("events from the old source must not survive", after.events.isEmpty())
        assertEquals(24, (after.source as FeedSource.Clock).hours)
    }

    @Test
    fun `a layout edit that does not change the source keeps its events`() {
        val engine = FeedSlotEngine()
        val slot = FeedSlot.of(FeedSource.People(name = "devs", pubkeys = listOf("a".repeat(64))))
        engine.sync(HomeLayout(slots = listOf(slot)))
        engine.runtime(slot.id)!!.events.add(note("kept", 1))

        // Toggling visibility is a layout edit that does not touch the source, so
        // what is already loaded must survive it.
        engine.sync(HomeLayout(slots = listOf(slot.copy(visible = false))))
        assertEquals(listOf("kept"), engine.runtime(slot.id)!!.events.map { it.id })
    }

    // ── Layout changes ───────────────────────────────────────────────────────

    @Test
    fun `removing a slot drops its runtime and its subscriptions`() {
        val engine = FeedSlotEngine()
        val keep = FeedSlot.of(FeedSource.Follows())
        val drop = FeedSlot.of(FeedSource.Hashtag(tags = listOf("x")))
        engine.sync(HomeLayout(slots = listOf(keep, drop)))
        engine.activate(drop.id, query())
        engine.registerSub(drop.id, "sub-drop")

        engine.sync(HomeLayout(slots = listOf(keep)))
        assertNull(engine.runtime(drop.id))
        assertNull(engine.slotForSub("sub-drop"))
    }

    @Test
    fun `removing the active slot clears the active id`() {
        val engine = FeedSlotEngine()
        val keep = FeedSlot.of(FeedSource.Follows())
        val drop = FeedSlot.of(FeedSource.Hashtag(tags = listOf("x")))
        engine.sync(HomeLayout(slots = listOf(keep, drop)))
        engine.activate(drop.id, query())
        assertEquals(drop.id, engine.activeSlotId)

        engine.sync(HomeLayout(slots = listOf(keep)))
        assertNull(engine.activeSlotId)
    }

    @Test
    fun `adding a slot does not disturb the others`() {
        val engine = FeedSlotEngine()
        val first = FeedSlot.of(FeedSource.Follows())
        engine.sync(HomeLayout(slots = listOf(first)))
        engine.activate(first.id, query())
        engine.markSettled(first.id)
        engine.runtime(first.id)!!.events.add(note("keep", 1))

        val added = FeedSlot.of(FeedSource.Hashtag(tags = listOf("y")))
        engine.sync(HomeLayout(slots = listOf(first, added)))
        assertEquals(listOf("keep"), engine.runtime(first.id)!!.events.map { it.id })
        assertNotNull(engine.runtime(added.id))
    }

    // ── Account switch ───────────────────────────────────────────────────────

    @Test
    fun `an account switch clears every slot, not just the active one`() {
        val (engine, home) = engineWith(FeedSource.Follows(), FeedSource.Clock())
        val first = home.slots[0]
        val second = home.slots[1]
        engine.activate(first.id, query())
        engine.markLoaded(first.id, "alice")
        engine.markSettled(first.id)
        engine.runtime(first.id)!!.events.add(note("alice-note", 1))
        engine.activate(second.id, query())
        engine.markLoaded(second.id, "alice")
        engine.markSettled(second.id)
        engine.runtime(second.id)!!.events.add(note("alice-clock", 1))

        engine.resetForAccountSwitch()

        engine.all().forEach { runtime ->
            assertNull("${runtime.slotId} still belongs to the old account", runtime.loadedPubkey)
            assertTrue("${runtime.slotId} kept the old account's events", runtime.events.isEmpty())
            assertTrue("${runtime.slotId} kept subscriptions", runtime.subIds.isEmpty())
            assertFalse(runtime.settled)
        }
    }

    @Test
    fun `a slot loaded for another account reports it does not belong`() {
        val (engine, home) = engineWith(FeedSource.Follows())
        val slot = home.slots[0]
        engine.activate(slot.id, query())
        engine.markLoaded(slot.id, "alice")
        assertTrue(engine.runtime(slot.id)!!.belongsTo("alice"))
        assertFalse(engine.runtime(slot.id)!!.belongsTo("bob"))
    }

    // ── Buffering ────────────────────────────────────────────────────────────

    @Test
    fun `events buffer until the slot settles, then flush in one go`() {
        val (engine, home) = engineWith(FeedSource.Follows())
        val slot = home.slots[0]
        engine.activate(slot.id, query())
        val runtime = engine.runtime(slot.id)!!

        runtime.buffer(note("early-1", 10))
        runtime.buffer(note("early-2", 20))
        assertTrue("nothing should be visible before settling", runtime.events.isEmpty())

        engine.markSettled(slot.id)
        assertEquals(listOf("early-2", "early-1"), runtime.events.map { it.id })

        // After settling, new events appear immediately.
        runtime.buffer(note("live", 30))
        assertTrue(runtime.events.any { it.id == "live" })
    }

    @Test
    fun `snapshots are newest first and capped`() {
        val (engine, home) = engineWith(FeedSource.Follows())
        val slot = home.slots[0]
        engine.activate(slot.id, query())
        val runtime = engine.runtime(slot.id)!!
        listOf(1L, 2L, 3L).forEach { ts -> runtime.events.add(note("n$ts", ts)) }
        assertEquals(listOf("n3", "n2"), runtime.snapshot(2).map { it.id })
    }

    private fun note(id: String, createdAt: Long) = Event(
        id = id,
        pubkey = "p".repeat(64),
        createdAt = createdAt,
        kind = 1,
        tags = emptyList(),
        content = "note $id",
        sig = "",
    )
}
