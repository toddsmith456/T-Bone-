package social.tbone.reactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.EventKind
import social.tbone.profile.ProfileRepository

class EngagementSubscriptionCoordinatorTest {

    @Test
    fun buildEngagementFiltersCombinesReactionsAndRepliesInSingleFilter() {
        val filters = EngagementSubscriptionCoordinator.buildEngagementFilters(
            noteIds = listOf("n1", "n2"),
            pollIds = emptyList(),
        )
        assertEquals(1, filters.size)
        assertEquals(listOf("n1", "n2"), filters[0].eTags)
        assertEquals(
            listOf(EventKind.REACTION, EventKind.TEXT_NOTE, EventKind.COMMENT),
            filters[0].kinds,
        )
    }

    @Test
    fun buildEngagementFiltersAddsPollResponseFilterOnlyWhenPollsPresent() {
        val filters = EngagementSubscriptionCoordinator.buildEngagementFilters(
            noteIds = listOf("n1", "p1"),
            pollIds = listOf("p1"),
        )
        assertEquals(2, filters.size)
        assertEquals(
            listOf(EventKind.REACTION, EventKind.TEXT_NOTE, EventKind.COMMENT),
            filters[0].kinds,
        )
        assertEquals(listOf("p1"), filters[1].eTags)
        assertEquals(listOf(EventKind.POLL_RESPONSE), filters[1].kinds)
    }

    @Test
    fun buildEngagementFiltersSupportsPollsOnly() {
        val filters = EngagementSubscriptionCoordinator.buildEngagementFilters(
            noteIds = emptyList(),
            pollIds = listOf("p1", "p2"),
        )
        assertEquals(1, filters.size)
        assertEquals(listOf("p1", "p2"), filters[0].eTags)
        assertEquals(listOf(EventKind.POLL_RESPONSE), filters[0].kinds)
    }

    @Test
    fun chunkEngagementBatchesPairsNoteAndPollChunksAndDeduplicates() {
        val notes = (1..5).map { "note$it" } + listOf("note1", "note2")
        val polls = listOf("poll1", "poll2", "poll1")
        val batches = EngagementSubscriptionCoordinator.chunkEngagementBatches(
            noteIds = notes,
            pollIds = polls,
            maxPerFilter = 2,
        )
        // 5 unique notes chunked by 2 -> 3 batches; 2 unique polls fit in batch 0.
        assertEquals(3, batches.size)
        assertEquals(2, batches[0].size)
        assertEquals(listOf("note1", "note2"), batches[0][0].eTags)
        assertEquals(listOf("poll1", "poll2"), batches[0][1].eTags)
        assertEquals(1, batches[1].size)
        assertEquals(listOf("note3", "note4"), batches[1][0].eTags)
        assertEquals(1, batches[2].size)
        assertEquals(listOf("note5"), batches[2][0].eTags)
    }

    @Test
    fun chunkEngagementBatchesEmptyReturnsEmpty() {
        assertTrue(EngagementSubscriptionCoordinator.chunkEngagementBatches(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun chunkMetadataAuthorsTrimsDeduplicatesAndSplits() {
        val chunks = ProfileRepository.chunkMetadataAuthors(
            pubkeys = listOf(" pk1 ", "", "   ", "pk2", "pk1", "pk3", "pk4", "pk5"),
            maxPerFilter = 2,
        )
        assertEquals(
            listOf(listOf("pk1", "pk2"), listOf("pk3", "pk4"), listOf("pk5")),
            chunks,
        )
    }
}
