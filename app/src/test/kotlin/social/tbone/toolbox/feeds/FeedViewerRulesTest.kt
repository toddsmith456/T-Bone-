package social.tbone.toolbox.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.feed.TimelineSupport
import social.tbone.home.FeedSource
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

/**
 * The viewer's own rules, without a relay.
 *
 * What is worth testing here is not the plumbing but the two decisions that are
 * easy to get wrong and invisible when they are: which events a timeline refuses
 * outright, and how pasted keys are parsed. Everything else in the viewer is a
 * relay round trip, which is CI's job to compile and nobody's to pretend is
 * verified.
 */
class FeedViewerRulesTest {

    private fun event(kind: Int, pubkey: String = "a".repeat(64), createdAt: Long = 1_000L) = Event(
        id = "id-$kind-$createdAt",
        pubkey = pubkey,
        createdAt = createdAt,
        kind = kind,
        tags = emptyList(),
        content = "",
        sig = "",
    )

    // ── What a timeline may show ─────────────────────────────────────────────

    @Test fun structuralEventsNeverBecomeTimelineRows() {
        // A contact list or a relay list has no readable content. Showing one as
        // a note was a real bug class in clients that filter on "not a DM" only.
        assertTrue(!TimelineSupport.admitsContent(event(EventKind.METADATA)))
        assertTrue(!TimelineSupport.admitsContent(event(EventKind.FOLLOW_LIST)))
        assertTrue(!TimelineSupport.admitsContent(event(EventKind.RELAY_LIST)))
    }

    @Test fun normalContentIsAdmitted() {
        assertTrue(TimelineSupport.admitsContent(event(EventKind.TEXT_NOTE)))
        assertTrue(TimelineSupport.admitsContent(event(EventKind.REPOST)))
        assertTrue(TimelineSupport.admitsContent(event(EventKind.POLL)))
    }

    @Test fun privateMessagingIsAlwaysRefused() {
        // The one hard rule: NIP-17 and its wrappers must never surface in a
        // public timeline, whatever feed type asked for them.
        // The four NIP-17/59 kinds, listed explicitly: if a new private kind is
        // added to isPrivateMessaging this test should fail and be updated, not
        // silently pass.
        listOf(EventKind.PRIVATE_DM, EventKind.ENCRYPTED_DM, EventKind.SEAL, EventKind.GIFT_WRAP)
            .forEach { kind ->
                assertTrue("kind $kind must be refused", !TimelineSupport.admitsContent(event(kind)))
            }
    }

    // ── Profile requests ─────────────────────────────────────────────────────

    @Test fun metadataRequestsAreChunkedRatherThanSentInOneGiantReq() {
        val authors = (1..400).map { it.toString().padStart(64, '0') }
        val filters = TimelineSupport.metadataFilters(authors)
        assertTrue("400 authors must not become one REQ", filters.size > 1)
        // Every author is asked for exactly once.
        assertEquals(authors.toSet(), filters.flatMap { it.authors.orEmpty() }.toSet())
        assertTrue(filters.all { (it.authors?.size ?: 0) <= 150 })
        assertTrue(filters.all { it.kinds == listOf(EventKind.METADATA) })
    }

    @Test fun metadataRequestsIgnoreThingsThatAreNotKeys() {
        val filters = TimelineSupport.metadataFilters(listOf("", "npub1short", "a".repeat(64)))
        assertEquals(listOf("a".repeat(64)), filters.flatMap { it.authors.orEmpty() })
    }

    @Test fun noAuthorsMeansNoRequests() {
        assertEquals(emptyList<social.tbone.nostr.Filter>(), TimelineSupport.metadataFilters(emptyList()))
    }

    // ── Key parsing ──────────────────────────────────────────────────────────

    /** The launcher's parser, without constructing a ViewModel. */
    private fun parse(raw: String): List<String> {
        val hex = "0123456789abcdefABCDEF"
        return raw.split('\n', ',', ' ', '\t', ';', '"', '\'')
            .mapNotNull { chunk ->
                val trimmed = chunk.trim().removePrefix("nostr:")
                when {
                    trimmed.length == 64 && trimmed.all { it in hex } -> trimmed.lowercase()
                    trimmed.startsWith("npub1") -> social.tbone.nostr.Nip19.npubToHex(trimmed)?.lowercase()
                    else -> null
                }
            }
            .distinct()
    }

    @Test fun oneBadKeyDoesNotDiscardTheGoodOnes() {
        val good = "b".repeat(64)
        val parsed = parse("garbage\n$good\n\nnothex")
        assertEquals(listOf(good), parsed)
    }

    @Test fun separatorsAreForgiving() {
        val key = "c".repeat(64)
        assertEquals(listOf(key), parse("$key,,\n\t  $key"))
    }

    @Test fun duplicateKeysCollapse() {
        val key = "d".repeat(64)
        assertEquals(1, parse("$key $key\n$key").size)
    }

    @Test fun capitalHexIsLowercased() {
        val upper = "A".repeat(64)
        assertEquals(listOf(upper.lowercase()), parse(upper))
    }

    @Test fun aShortHexIsNotAKey() {
        assertEquals(emptyList<String>(), parse("a".repeat(63)))
    }

    @Test fun nostrPrefixIsStrippedBeforeValidation() {
        val key = "e".repeat(64)
        assertEquals(listOf(key), parse("nostr:$key"))
    }

    @Test fun nonsenseParsesToNothingRatherThanThrowing() {
        assertEquals(emptyList<String>(), parse(""))
        assertEquals(emptyList<String>(), parse("hello world"))
    }

    // ── Tab state ────────────────────────────────────────────────────────────

    @Test fun aFreshTabBuffersUntilItSettles() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Hashtag(tags = listOf("bitcoin")))
        tab.buffer(event(EventKind.TEXT_NOTE, createdAt = 10))
        assertEquals("nothing shows before the tab settles", 0, tab.events.size)
        assertEquals(1, tab.pending.size)
        tab.settled = true
        tab.flush()
        assertEquals(1, tab.events.size)
        assertEquals(0, tab.pending.size)
    }

    @Test fun aSettledTabAcceptsEventsDirectly() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Follows())
        tab.settled = true
        tab.buffer(event(EventKind.TEXT_NOTE, createdAt = 10))
        assertEquals(1, tab.events.size)
        assertEquals(0, tab.pending.size)
    }

    @Test fun aTabOrdersNewestFirstAndDropsDuplicates() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Follows())
        tab.settled = true
        val older = event(EventKind.TEXT_NOTE, createdAt = 10)
        val newer = event(EventKind.TEXT_NOTE, createdAt = 20)
        tab.buffer(older)
        tab.buffer(newer)
        tab.buffer(newer) // same id: a second relay delivering it must not duplicate
        assertEquals(listOf(newer, older), tab.snapshot(10))
    }

    @Test fun clearingATabForgetsItsNotesAndItsCursor() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Follows())
        tab.settled = true
        tab.buffer(event(EventKind.TEXT_NOTE, createdAt = 10))
        tab.clear()
        assertEquals(0, tab.events.size)
        assertEquals("an emptied tab has no paging cursor", 0L, tab.oldestAt)
    }

    @Test fun theOldestNoteIsThePagingCursor() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Follows())
        tab.settled = true
        tab.buffer(event(EventKind.TEXT_NOTE, createdAt = 50))
        tab.buffer(event(EventKind.TEXT_NOTE, createdAt = 90))
        assertEquals(50L, tab.oldestAt)
    }

    @Test fun snapshotsAreCappedSoALongLivedTabCannotGrowForever() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Global())
        tab.settled = true
        (1..50).forEach { tab.buffer(event(EventKind.TEXT_NOTE, createdAt = it.toLong())) }
        assertEquals(10, tab.snapshot(10).size)
        assertEquals("the cap keeps the newest notes", 50L, tab.snapshot(10).first().createdAt)
    }

    @Test fun tabLabelsAreShortEnoughToBeASubscriptionLabel() {
        val tab = FeedTabState(tabId = "abcdefgh-1234", source = FeedSource.Follows())
        assertTrue(tab.label().length <= 16)
        assertTrue(tab.label().startsWith("viewer-"))
    }

    @Test fun aTabStartsWithNothingBlocked() {
        val tab = FeedTabState(tabId = "t1", source = FeedSource.Relay(urls = listOf("wss://relay.example")))
        assertNull(tab.blockedReason)
        assertTrue(!tab.settled)
        assertTrue(tab.subIds.isEmpty())
    }
}
