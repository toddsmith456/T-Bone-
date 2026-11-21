package social.tbone.media.audio

import org.junit.Assert.*
import org.junit.Test
import social.tbone.settings.CachePreset

class AudioCachePolicyTest {
    @Test fun boundedPresetBudgets() {
        for (preset in CachePreset.entries) assertTrue(AudioCachePolicy.diskBudget(preset) in 1048576L..16777216L)
        assertTrue(AudioCachePolicy.diskBudget(CachePreset.TURBO) >= AudioCachePolicy.diskBudget(CachePreset.COMPACT))
    }
    @Test fun signedUrlKeysMustNotCollide() {
        assertNotEquals(AudioCachePolicy.key("https://a/song?token=a"), AudioCachePolicy.key("https://a/song?token=b"))
        assertEquals(64, AudioCachePolicy.key("https://a/song").length)
    }
    @Test fun negativeCachingHasShortBackoff() {
        assertTrue(AudioCachePolicy.fresh(0, 1000, false))
        assertFalse(AudioCachePolicy.fresh(0, AudioCachePolicy.FAILURE_TTL_MS, false))
        assertTrue(AudioCachePolicy.fresh(0, AudioCachePolicy.FAILURE_TTL_MS, true))
        assertFalse(AudioCachePolicy.fresh(1000, 0, true))
    }
}
