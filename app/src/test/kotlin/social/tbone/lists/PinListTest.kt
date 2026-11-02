package social.tbone.lists

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

class PinListTest {
    @Test fun parsesAmethystKind10001TagsInOrderAndIgnoresBadEntries() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        fun tag(vararg v: String) = JsonArray(v.map(::JsonPrimitive))
        val list = Event("0".repeat(64), "1".repeat(64), 1, EventKind.PIN_LIST,
            listOf(tag("e", first, "wss://relay.example"), tag("p", second),
                tag("e", "invalid"), tag("e", first), tag("e", second)), "", "")
        assertEquals(listOf(first, second), pinEventIds(list))
    }
}
