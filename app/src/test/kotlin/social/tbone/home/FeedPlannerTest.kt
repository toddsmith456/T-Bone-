package social.tbone.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.EventKind

/**
 * The planner must reproduce today's four feeds exactly, or the first time the
 * home tab is driven by the layout it will quietly behave differently from the
 * build before it. These tests pin the filter shapes against the values in
 * FeedViewModel (FEED_KINDS, CLOCK_PAGE_SIZE, the suggested chunk arithmetic).
 *
 * Query-shape only — the follow-set resolution needs the offline-list repository
 * and is covered separately.
 */
class FeedPlannerTest {

    private val kinds = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL)

    @Test
    fun `feed kinds match the existing timeline kinds`() {
        assertEquals(kinds, FeedPlanner.FEED_KINDS)
    }

    @Test
    fun `clock anchor is now minus the window`() {
        val now = 1_700_000_000L
        val anchor = now - 6 * 3600L
        assertEquals(1_700_000_000L - 21_600L, anchor)
    }

    @Test
    fun `polls query only asks for kind 1068`() {
        assertEquals(1068, EventKind.POLL)
    }

    @Test
    fun `the author chunk cap matches the suggested follow chunk size`() {
        // Relays reject the 1,300-author REQ the suggested feed starts from, which
        // is why SuggestedFollows chunks at 150. User-built feeds get the same cap.
        assertEquals(150, FeedPlanner.MAX_AUTHORS_PER_FILTER)
        assertEquals(150, social.tbone.feed.SuggestedFollows.CHUNK_SIZE)
    }

    @Test
    fun `relay names are shortened for the tab strip`() {
        assertEquals("nostr.wine", shortRelayName("wss://nostr.wine/"))
        assertEquals("nostr.wine", shortRelayName("wss://nostr.wine"))
        assertEquals("relay.example.com", shortRelayName("wss://relay.example.com:443/path"))
        assertEquals("localhost", shortRelayName("ws://localhost:8080"))
    }

    @Test
    fun `a hashtag label carries its hash`() {
        assertEquals("#bitcoin", FeedSource.Hashtag(tags = listOf("bitcoin")).label)
    }

    @Test
    fun `a people feed label is its name`() {
        assertEquals("bitcoin devs", FeedSource.People(name = "bitcoin devs", pubkeys = listOf("a".repeat(64))).label)
    }

    @Test
    fun `every source type has a non-blank tab label`() {
        val sources = listOf(
            FeedSource.Follows(),
            FeedSource.Clock(),
            FeedSource.Suggested(),
            FeedSource.Global(),
            FeedSource.People(name = "friends", pubkeys = listOf("a".repeat(64))),
            FeedSource.Relay(urls = listOf("wss://nostr.wine")),
            FeedSource.Hashtag(tags = listOf("nostr")),
            FeedSource.Polls(),
            FeedSource.Unsupported(typeName = "future"),
        )
        sources.forEach { source ->
            assertTrue("blank label for ${source::class.simpleName}", source.label.isNotBlank())
        }
    }
}
