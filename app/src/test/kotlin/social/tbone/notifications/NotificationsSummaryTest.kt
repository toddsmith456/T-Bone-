package social.tbone.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationsSummaryTest {
    @Test
    fun `summary uses epoch seconds and includes only the past day`() {
        val now = 1_800_000_000L
        val items = listOf(
            FlatNotifItem("reply-now", NotifType.REPLY, "a", now),
            FlatNotifItem("reaction-edge", NotifType.REACTION, "b", now - 86_400L),
            FlatNotifItem("old-mention", NotifType.MENTION, "c", now - 86_401L),
            FlatNotifItem("future-vote", NotifType.VOTE, "d", now + 60L),
            FlatNotifItem("calendar", NotifType.CALENDAR, "calendar", now + 60L),
        )

        val summary = summarizeNotifications(items, now)

        assertEquals(1, summary.replyCount)
        assertEquals(1, summary.reactionCount)
        assertEquals(0, summary.mentionCount)
        assertEquals(0, summary.voteCount)
        assertEquals(1, summary.calendarCount)
    }
}
