package social.tbone.notifications

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import social.tbone.nostr.DailyTokens
import social.tbone.nostr.Event
import social.tbone.nostr.Nip13

class SteakNotificationTest {

    private val me = "a".repeat(64)
    private val sender = "b".repeat(64)
    private val someoneElse = "c".repeat(64)

    @Test
    fun `classifyNotification accepts valid 16-bit PoW steak offer addressed to me`() = runBlocking {
        val now = System.currentTimeMillis() / 1000L
        val mined = Nip13.mine(
            DailyTokens.buildUnsignedOffer(sender, me, DailyTokens.STEAK_SLUG, now),
            DailyTokens.MIN_POW_BITS,
        )
        val offerEvent = Event(
            id = mined.computeId(),
            pubkey = mined.pubkey,
            createdAt = mined.createdAt,
            kind = mined.kind,
            tags = mined.tags,
            content = mined.content,
            sig = "0".repeat(128),
        )

        assertEquals(NotificationClass.STEAK, classifyNotification(offerEvent, me) { false })
        assertNull("Offer addressed to someone else must not notify me", classifyNotification(offerEvent, someoneElse) { false })
        assertNull("Self-authored offer must not notify sender", classifyNotification(offerEvent, sender) { false })
    }

    @Test
    fun `classifyNotification rejects steak offers without 16-bit PoW`() {
        val now = System.currentTimeMillis() / 1000L
        val unmined = DailyTokens.buildUnsignedOffer(sender, me, DailyTokens.STEAK_SLUG, now)
        val event = Event(
            id = unmined.computeId(),
            pubkey = unmined.pubkey,
            createdAt = unmined.createdAt,
            kind = unmined.kind,
            tags = unmined.tags,
            content = unmined.content,
            sig = "0".repeat(128),
        )
        assertNull(classifyNotification(event, me) { false })
    }

    @Test
    fun `summarizeNotifications counts steaks within the 24h window`() {
        val now = 1_800_000_000L
        val items = listOf(
            FlatNotifItem("steak-1", NotifType.STEAK, sender, now - 120L),
            FlatNotifItem("steak-expired", NotifType.STEAK, sender, now - 86_401L),
        )
        val summary = summarizeNotifications(items, now)
        assertEquals(1, summary.steakCount)
    }
}
