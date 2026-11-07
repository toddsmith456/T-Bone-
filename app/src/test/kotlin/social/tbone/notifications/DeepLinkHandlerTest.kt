package social.tbone.notifications

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepLinkHandlerTest {
    @Test fun retainsColdStartEventsUntilCollected() = runBlocking {
        val handler = DeepLinkHandler()
        handler.navigateToThread("event-id")
        handler.navigateToProfile("pubkey")
        assertEquals("event-id", handler.openThread.first())
        assertEquals("pubkey", handler.openProfile.first())
    }
}
