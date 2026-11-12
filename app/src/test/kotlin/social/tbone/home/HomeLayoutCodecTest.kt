package social.tbone.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout codec is the one place a bad value can take the home screen down on
 * launch, so every failure mode in the blueprint's edge-case table is a test.
 */
class HomeLayoutCodecTest {

    private fun slotJson(type: String, extra: String = "", id: String = "s1", visible: Boolean = true) =
        """{"id":"$id","visible":$visible,"source":{"type":"$type"$extra}}"""

    private fun layoutJson(vararg slots: String, lastActive: String? = null) =
        """{"version":1,"slots":[${slots.joinToString(",")}]${lastActive?.let { ""","lastActiveSlotId":"$it"""" } ?: ""}}"""

    // ── Round trip ───────────────────────────────────────────────────────────

    @Test
    fun `round-trips every source type`() {
        val layout = HomeLayout(
            slots = listOf(
                FeedSlot.of(FeedSource.Follows()),
                FeedSlot.of(FeedSource.Clock(hours = 3)),
                FeedSlot.of(FeedSource.Suggested()),
                FeedSlot.of(FeedSource.Global()),
                FeedSlot.of(
                    FeedSource.People(
                        name = "bitcoin devs",
                        pubkeys = listOf("a".repeat(64), "b".repeat(64)),
                        origin = PeopleOrigin.NIP51_SET,
                        dTag = "devs",
                    ),
                ),
                FeedSlot.of(FeedSource.Relay(urls = listOf("wss://nostr.wine"), authors = listOf("c".repeat(64)))),
                FeedSlot.of(FeedSource.Hashtag(tags = listOf("bitcoin", "nostr"))),
                FeedSlot.of(FeedSource.Polls(authors = listOf("d".repeat(64)))),
            ),
            lastActiveSlotId = null,
        )
        val decoded = HomeLayoutCodec.decode(HomeLayoutCodec.encode(layout))
        assertNotNull(decoded)
        assertEquals(layout.slots.map { it.id }, decoded!!.slots.map { it.id })
        assertEquals(layout.slots.map { it.source }, decoded.slots.map { it.source })
        assertEquals(layout.slots.map { it.visible }, decoded.slots.map { it.visible })
    }

    @Test
    fun `round-trips the remembered active slot`() {
        val layout = HomeLayout.default()
        val withActive = layout.copy(lastActiveSlotId = layout.slots[2].id)
        val decoded = HomeLayoutCodec.decode(HomeLayoutCodec.encode(withActive))
        assertEquals(layout.slots[2].id, decoded!!.lastActiveSlotId)
    }

    // ── Absence and defaults ─────────────────────────────────────────────────

    @Test
    fun `an absent layout decodes to null so the caller keeps its defaults`() {
        assertNull(HomeLayoutCodec.decode(null))
        assertNull(HomeLayoutCodec.decode(""))
        assertNull(HomeLayoutCodec.decode("   "))
    }

    @Test
    fun `the default layout is the four shipped tabs in order`() {
        val defaults = HomeLayout.default()
        assertEquals(4, defaults.slots.size)
        assertTrue(defaults.slots[0].source is FeedSource.Follows)
        assertTrue(defaults.slots[1].source is FeedSource.Clock)
        assertTrue(defaults.slots[2].source is FeedSource.Suggested)
        assertTrue(defaults.slots[3].source is FeedSource.Global)
        assertTrue(defaults.slots.all { it.visible })
        assertEquals("FOLLOWS", defaults.slots.first().source.label)
    }

    // ── Tolerance ────────────────────────────────────────────────────────────

    @Test
    fun `an unknown feed type becomes a placeholder slot, not a crash`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("follows", id = "a"), slotJson("time-machine", id = "b")),
        )
        assertNotNull(decoded)
        assertEquals(2, decoded!!.slots.size)
        val unknown = decoded.slots[1].source
        assertTrue(unknown is FeedSource.Unsupported)
        assertEquals("time-machine", (unknown as FeedSource.Unsupported).typeName)
    }

    @Test
    fun `an unreadable slot is dropped and the rest survive`() {
        val decoded = HomeLayoutCodec.decode(
            """{"slots":[{"id":"a","source":{"type":"follows"},"visible":true},{"source":"nonsense"}]}""",
        )
        assertNotNull(decoded)
        assertEquals(1, decoded!!.slots.size)
        assertTrue(decoded.slots[0].source is FeedSource.Follows)
    }

    @Test
    fun `malformed JSON is ignored rather than thrown`() {
        assertNull(HomeLayoutCodec.decode("{not json"))
        assertNull(HomeLayoutCodec.decode("[]"))
        assertNull(HomeLayoutCodec.decode("""{"slots":"not-an-array"}"""))
    }

    @Test
    fun `an oversized payload is rejected before parsing`() {
        val huge = "x".repeat(HomeLayoutCodec.MAX_PAYLOAD_BYTES + 1)
        assertNull(HomeLayoutCodec.decode(huge))
    }

    @Test
    fun `clock hours are clamped into the supported range`() {
        val tooBig = HomeLayoutCodec.decode(layoutJson(slotJson("clock", ""","hours":999""")))
        assertEquals(24, (tooBig!!.slots[1].source as FeedSource.Clock).hours)
        val tooSmall = HomeLayoutCodec.decode(layoutJson(slotJson("clock", ""","hours":-5""")))
        assertEquals(1, (tooSmall!!.slots[1].source as FeedSource.Clock).hours)
    }

    @Test
    fun `a people feed with no valid pubkeys is dropped`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("follows", id = "a"), slotJson("people", ""","name":"empty","pubkeys":[]""", id = "b")),
        )
        assertEquals(1, decoded!!.slots.size)
    }

    @Test
    fun `hashtags are normalised on read`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("hashtag", ""","tags":["#Bitcoin","bitcoin","#nostr"]""")),
        )
        assertEquals(listOf("bitcoin", "nostr"), (decoded!!.slots[1].source as FeedSource.Hashtag).tags)
    }

    // ── Invariants ───────────────────────────────────────────────────────────

    @Test
    fun `a layout with no visible slot un-hides the first one`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("follows", id = "a", visible = false), slotJson("clock", id = "b", visible = false)),
        )
        assertTrue("home must never be empty", decoded!!.visibleSlots.isNotEmpty())
    }

    @Test
    fun `the follow slot is forced visible and first even if stored hidden or late`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("clock", id = "b"), slotJson("follows", id = "a", visible = false)),
        )
        assertEquals(2, decoded!!.slots.size)
        assertTrue(decoded.slots[0].source is FeedSource.Follows)
        assertTrue(decoded.slots[0].visible)
    }

    @Test
    fun `a layout missing the follow slot gets one added`() {
        val decoded = HomeLayoutCodec.decode(layoutJson(slotJson("global", id = "b")))
        assertEquals(2, decoded!!.slots.size)
        assertTrue(decoded.slots[0].source is FeedSource.Follows)
    }

    @Test
    fun `two follow slots are collapsed to one`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("follows", id = "a"), slotJson("follows", id = "b"), slotJson("clock", id = "c")),
        )
        assertEquals(2, decoded!!.slots.size)
        assertEquals(1, decoded.slots.count { it.source is FeedSource.Follows })
    }

    @Test
    fun `a remembered active slot that is hidden falls back to the first visible`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(
                slotJson("follows", id = "a"),
                slotJson("clock", id = "b", visible = false),
                lastActive = "b",
            ),
        )
        assertNull(decoded!!.lastActiveSlotId)
        assertEquals("a", decoded.landingSlot?.id)
    }

    @Test
    fun `a remembered active slot that is visible is honoured`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(slotJson("follows", id = "a"), slotJson("clock", id = "b"), lastActive = "b"),
        )
        assertEquals("b", decoded!!.landingSlot?.id)
    }

    @Test
    fun `two clock feeds at different windows coexist`() {
        val decoded = HomeLayoutCodec.decode(
            layoutJson(
                slotJson("follows", id = "a"),
                slotJson("clock", ""","hours":1""", id = "hour"),
                slotJson("clock", ""","hours":24""", id = "day"),
            ),
        )
        val hours = decoded!!.slots.mapNotNull { (it.source as? FeedSource.Clock)?.hours }
        assertEquals(listOf(1, 24), hours)
        assertFalse(hours.isEmpty())
    }
}
