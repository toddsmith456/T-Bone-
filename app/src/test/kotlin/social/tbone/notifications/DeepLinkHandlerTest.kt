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
    @Test fun activityRecreationDoesNotReplayWhileQueueLivesButProcessRestartCanRecover() {
        val original = DeepLinkHandler()
        assertEquals(true, shouldHandleLaunchIntent(null, original.processToken))
        assertEquals(false, shouldHandleLaunchIntent(original.processToken, original.processToken))
        val restarted = DeepLinkHandler()
        assertEquals(true, shouldHandleLaunchIntent(original.processToken, restarted.processToken))
    }

    @Test fun multipleCollectorsConsumeEachQueuedDestinationOnlyOnce() = runBlocking {
        val handler = DeepLinkHandler()
        handler.navigateToThread("first")
        handler.navigateToProfile("second")
        assertEquals(PendingNavigation.Thread("first"), handler.destinations.first())
        assertEquals(PendingNavigation.Profile("second"), handler.destinations.first())
        val none = kotlinx.coroutines.withTimeoutOrNull(50) { handler.destinations.first() }
        assertEquals(null, none)
    }
}
