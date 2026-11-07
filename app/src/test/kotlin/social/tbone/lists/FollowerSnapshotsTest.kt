package social.tbone.lists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerSnapshotsTest {
    @Test fun unionRetainsBothSamplesAndDeduplicatesCase() {
        val samples = FollowerSamples()
        val first = samples.merge(listOf("AA", "BB"), "relays")!!
        assertEquals(listOf("aa", "bb"), first.followers)
        val merged = samples.merge(listOf("bb", "cc"), "primal.net")!!
        assertEquals(listOf("aa", "bb", "cc"), merged.followers)
        assertEquals("primal.net + relays", merged.source)
        assertNull(samples.merge(listOf("aa"), "relays"))
    }

    @Test fun capIsEnforcedAndSourceCoverageIsVisible() {
        val samples = FollowerSamples(maxEntries = 2)
        assertEquals(listOf("a", "b"), samples.merge(listOf("a", "b", "c"), "relays")!!.followers)
        val result = samples.merge(listOf("d"), "primal.net")!!
        assertEquals(listOf("a", "b"), result.followers)
        assertTrue(result.source.contains("primal.net"))
    }

    @Test fun cacheExpiresAndDoesNotMixRelayConfigurations() {
        var now = 1000L
        val cache = FollowerSnapshotCache { now }
        val state = FollowerListRepository.FollowersState(followers = listOf("a"), loading = false)
        cache.put("user", listOf("wss://b/", "wss://a"), state, cache.generation())
        assertEquals(state, cache.get("user", listOf("wss://a/", "wss://b")))
        assertNull(cache.get("user", listOf("wss://different")))
        now += 5 * 60_000L
        assertNull(cache.get("user", listOf("wss://a", "wss://b")))
    }

    @Test fun invalidationPreventsAnOldFetchFromRepopulatingCache() {
        val cache = FollowerSnapshotCache { 1000L }
        val state = FollowerListRepository.FollowersState(followers = listOf("a"), loading = false)
        val before = cache.generation()
        cache.invalidate("user")
        cache.put("user", emptyList(), state, before)
        assertNull(cache.get("user", emptyList()))
        cache.put("user", emptyList(), state, cache.generation())
        assertEquals(state, cache.get("user", emptyList()))
        cache.clear()
        assertNull(cache.get("user", emptyList()))
    }
}
