package social.tbone.ui.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedPerformanceTuningsTest {

    @Test
    fun initialFeedLimitScalesDownWithRelayCount() {
        assertEquals(150, FeedViewModel.initialFeedLimitPerRelay(0))
        assertEquals(150, FeedViewModel.initialFeedLimitPerRelay(1))
        assertEquals(100, FeedViewModel.initialFeedLimitPerRelay(2))
        assertEquals(100, FeedViewModel.initialFeedLimitPerRelay(3))
        assertEquals(60, FeedViewModel.initialFeedLimitPerRelay(4))
        assertEquals(60, FeedViewModel.initialFeedLimitPerRelay(11))
    }
}
