package social.tbone.notifications

import org.junit.Assert.assertEquals
import org.junit.Test
import social.tbone.settings.NotifFilterNames

/**
 * The filter list exists twice: as the UI enum and as plain names in the
 * settings layer (which must not depend on a UI enum). Keeping them in step by
 * hand is what let ZAPS ship as a notification type with no matching default —
 * zaps then arrived and were filtered straight back out.
 */
class NotifFilterNamesTest {

    @Test
    fun `settings names match the UI filter enum exactly`() {
        assertEquals(
            NotifFilter.entries.map { it.name }.toSet(),
            NotifFilterNames.ALL,
        )
    }

    @Test
    fun `zaps are part of the default set`() {
        assertEquals(true, NotifFilterNames.ZAPS in NotifFilterNames.ALL)
    }

    @Test
    fun `every filter name resolves in both directions`() {
        NotifFilterNames.ALL.forEach { name ->
            val filter = NotifFilter.fromName(name)
            assertEquals("$name must resolve to a filter", name, filter?.name)
        }
        NotifFilter.entries.forEach { filter ->
            assertEquals(
                "${filter.name} must be in the default set",
                true,
                filter.name in NotifFilterNames.ALL,
            )
        }
    }
}
