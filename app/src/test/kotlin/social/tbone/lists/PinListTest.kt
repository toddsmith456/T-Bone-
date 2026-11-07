package social.tbone.lists

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

class PinListTest {
    @Test fun recognizesPinsBeyond256() {
        val ids = (0..300).map { it.toString(16).padStart(64, '0') }
        val tags = ids.map { JsonArray(listOf(JsonPrimitive("e"), JsonPrimitive(it))) }
        val list = Event("0".repeat(64), "1".repeat(64), 1, EventKind.PIN_LIST, tags, "", "")
        assertEquals(ids, pinEventIds(list))
    }

    @Test fun parsesAmethystKind10001TagsInOrderAndIgnoresBadEntries() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        fun tag(vararg v: String) = JsonArray(v.map(::JsonPrimitive))
        val list = Event("0".repeat(64), "1".repeat(64), 1, EventKind.PIN_LIST,
            listOf(tag("e", first, "wss://relay.example"), tag("p", second),
                tag("e", "invalid"), tag("e", first), tag("e", second)), "", "")
        assertEquals(listOf(first, second), pinEventIds(list))
    }

    @Test fun partialRelayReadCannotReplaceOfflineList() {
        val owner = "1".repeat(64)
        val event = Event("0".repeat(64), owner, 123, EventKind.FOLLOW_LIST,
            listOf(JsonArray(listOf(JsonPrimitive("p"), JsonPrimitive("a".repeat(64))))), "", "")
        assertEquals(OnlineFetch.Unreachable, classifyOnlineFetch(RelayListFetcher.Result(event, 1, 2), owner))
        assertEquals(OnlineFetch.Unreachable, classifyOnlineFetch(RelayListFetcher.Result(null, 0, 0), owner))
        assertEquals(OnlineFetch.Empty, classifyOnlineFetch(RelayListFetcher.Result(null, 2, 2), owner))
        val found = classifyOnlineFetch(RelayListFetcher.Result(event, 2, 2), owner) as OnlineFetch.Found
        assertEquals(listOf("a".repeat(64)), found.entries.map { it.pubkey })
    }


    @Test fun replaceableEventsUseLowestIdOnTimestampTie() {
        val a = Event("a".repeat(64), "1".repeat(64), 123, EventKind.FOLLOW_LIST, emptyList(), "", "")
        val b = a.copy(id = "b".repeat(64))
        assertEquals(a, newestListEvent(b, a))
        assertEquals(a, newestListEvent(a, b))
        assertEquals(b.copy(createdAt = 124), newestListEvent(a, b.copy(createdAt = 124)))
    }


    @Test fun followTogglePreservesUnrelatedAndExtendedTags() {
        fun tag(vararg values: String) = JsonArray(values.map(::JsonPrimitive))
        val owner = "1".repeat(64)
        val target = "2".repeat(64)
        val other = "3".repeat(64)
        val raw = listOf(tag("p", other, "wss://relay", "alias", "extension"),
            tag("p", "malformed"), tag("x", "keep"), tag("p", target, "wss://old"))
        val event = Event("0".repeat(64), owner, 1, EventKind.FOLLOW_LIST, raw, "content", "")
        assertEquals(raw.dropLast(1), followTagsForToggle(event, emptyList(), target, false))
        assertEquals(raw, followTagsForToggle(event, emptyList(), target, true))
        val result = followTagsForToggle(event, emptyList(), "4".repeat(64), true)
        assertEquals(raw, result.dropLast(1))
        assertEquals(tag("p", "4".repeat(64)), result.last())
    }

}
