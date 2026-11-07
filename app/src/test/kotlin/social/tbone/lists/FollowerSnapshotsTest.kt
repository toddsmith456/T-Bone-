package social.tbone.lists

import kotlinx.coroutines.async
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
    @Test fun simultaneousSourceUpdatesRetainBothSets() = kotlinx.coroutines.runBlocking {
        val samples = FollowerSamples(maxEntries = 1_000)
        val first = async(kotlinx.coroutines.Dispatchers.Default) { samples.merge((0 until 250).map { "r$it" }, "relays") }
        val second = async(kotlinx.coroutines.Dispatchers.Default) { samples.merge((0 until 250).map { "p$it" }, "primal.net") }
        first.await(); second.await()
        val final = samples.merge(listOf("last"), "relays")!!
        assertEquals(501, final.followers?.size)
        assertTrue(final.followers!!.containsAll((0 until 250).map { "r$it" }))
        assertTrue(final.followers!!.containsAll((0 until 250).map { "p$it" }))
    }

    @Test fun backwardsClockDoesNotKeepOldCacheIndefinitely() {
        var now = 10_000L
        val cache = FollowerSnapshotCache { now }
        val state = FollowerListRepository.FollowersState(followers = listOf("a"), loading = false)
        cache.put("user", emptyList(), state, cache.generation())
        now = 9_999L
        assertNull(cache.get("user", emptyList()))
    }
}
