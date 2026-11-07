package social.tbone.notifications

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepLinkHandlerTest {
    @Test fun retainsColdStartEventsInMixedOrderUntilCollected() = runBlocking {
        val handler = DeepLinkHandler()
        handler.navigateToThread("first")
        handler.navigateToProfile("second")
        handler.navigateToThread("third")
        assertEquals(listOf(PendingNavigation.Thread("first"), PendingNavigation.Profile("second"),
            PendingNavigation.Thread("third")), handler.destinations.take(3).toList())
    }

    @Test fun overflowRetainsTheMostRecentDestinations() = runBlocking {
        val handler = DeepLinkHandler()
        repeat(70) { handler.navigateToThread(it.toString()) }
        assertEquals(PendingNavigation.Thread("6"), handler.destinations.first())
    }
}
