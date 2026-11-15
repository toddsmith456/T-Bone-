package social.tbone.ui.feed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent

class ObservedNoteCardSlicingTest {

    private fun fakeEvent(
        id: String,
        pubkey: String,
        kind: Int = EventKind.TEXT_NOTE,
        content: String = "hello",
        tags: List<JsonArray> = emptyList(),
    ): Event = Event(
        id = id,
        pubkey = pubkey,
        createdAt = 1_730_000_000L,
        kind = kind,
        tags = tags,
        content = content,
        sig = "0".repeat(128),
    )

    @Test
    fun sliceQuotedEventsIgnoresUnrelatedQuotesAndIncludesOneNestedLevel() {
        val innerId = "c".repeat(64)
        val outerId = "b".repeat(64)
        val unrelatedId = "d".repeat(64)

        val inner = fakeEvent(id = innerId, pubkey = "3".repeat(64), content = "inner")
        val outer = fakeEvent(
            id = outerId,
            pubkey = "2".repeat(64),
            content = "quoting inner",
            tags = listOf(buildJsonArray { add("q"); add(innerId) }),
        )
        val unrelated = fakeEvent(id = unrelatedId, pubkey = "9".repeat(64), content = "noise")
        val repost = fakeEvent(
            id = "a".repeat(64),
            pubkey = "1".repeat(64),
            kind = EventKind.REPOST,
            content = "",
            tags = listOf(buildJsonArray { add("e"); add(outerId) }),
        )

        val directIds = collectCardQuoteIds(repost)
        assertEquals(setOf(outerId), directIds)

        val before = sliceQuotedEvents(mapOf(outerId to outer, innerId to inner), directIds)
        val afterUnrelated = sliceQuotedEvents(
            mapOf(outerId to outer, innerId to inner, unrelatedId to unrelated),
            directIds,
        )

        // Adding an unrelated quote must yield an equal slice so distinctUntilChanged() skips recomposition.
        assertEquals(setOf(outerId, innerId), before.keys)
        assertEquals(before, afterUnrelated)
    }

    @Test
    fun collectCardRelevantPubkeysIncludesAuthorPTagsInlineNpubAndQuotedAuthor() {
        val authorPk = "1".repeat(64)
        val pTagPk = "2".repeat(64)
        val inlinePk = "3".repeat(64)
        val quotedAuthorPk = "4".repeat(64)
        val unrelatedPk = "5".repeat(64)
        val inlineNpub = Nip19.hexToNpub(inlinePk)

        val note = fakeEvent(
            id = "a".repeat(64),
            pubkey = authorPk,
            content = "hi nostr:$inlineNpub",
            tags = listOf(buildJsonArray { add("p"); add(pTagPk) }),
        )
        val quoted = fakeEvent(
            id = "b".repeat(64),
            pubkey = quotedAuthorPk,
            content = "quoted text",
        )

        val relevant = collectCardRelevantPubkeys(note, listOf(quoted))
        assertEquals(setOf(authorPk, pTagPk, inlinePk, quotedAuthorPk), relevant)

        val baseProfiles = mapOf(
            authorPk to ProfileContent(name = "Alice"),
            quotedAuthorPk to ProfileContent(name = "Bob"),
        )
        val withUnrelated = baseProfiles + (unrelatedPk to ProfileContent(name = "Eve"))

        assertEquals(
            sliceProfiles(baseProfiles, relevant),
            sliceProfiles(withUnrelated, relevant),
        )
    }

    @Test
    fun extractInlineMentionPubkeysReturnsEmptyForPlainNotes() {
        assertTrue(extractInlineMentionPubkeys("just a normal note without mentions").isEmpty())
    }
}
