package social.tbone.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.profile.NegativeLookupEntry
import social.tbone.profile.ProfileRepository
import social.tbone.reactions.EngagementCacheRepository
import social.tbone.settings.AvatarMode
import social.tbone.settings.CachePreset

class CachePresetAndKeysTest {

    @Test
    fun legacyLightweightMigratesCleanlyToPresets() {
        assertEquals(CachePreset.COMPACT, CachePreset.fromPref(null, legacyLightweight = true))
        assertEquals(CachePreset.BALANCED, CachePreset.fromPref(null, legacyLightweight = false))
        assertEquals(CachePreset.BALANCED, CachePreset.fromPref(null, legacyLightweight = null))
        assertEquals(CachePreset.TURBO, CachePreset.fromPref("turbo", legacyLightweight = true))
        assertEquals(CachePreset.ULTRA_LIGHT, CachePreset.fromPref("ultra_light", legacyLightweight = false))
        assertEquals(CachePreset.PERFORMANCE, CachePreset.fromPref("performance", legacyLightweight = false))
    }

    @Test
    fun presetsIncreaseMonotonicallyInBudgets() {
        val ordered = listOf(
            CachePreset.ULTRA_LIGHT,
            CachePreset.COMPACT,
            CachePreset.BALANCED,
            CachePreset.PERFORMANCE,
            CachePreset.TURBO,
        )
        for (i in 0 until ordered.lastIndex) {
            val low = ordered[i]
            val high = ordered[i + 1]
            assertTrue("${low.name} < ${high.name} avatarDisk", low.avatarDiskBytes < high.avatarDiskBytes)
            assertTrue("${low.name} < ${high.name} mediaDisk", low.mediaDiskBytes < high.mediaDiskBytes)
            assertTrue("${low.name} < ${high.name} videoDisk", low.videoDiskBytes < high.videoDiskBytes)
            assertTrue("${low.name} < ${high.name} profiles", low.maxCachedProfiles < high.maxCachedProfiles)
            assertTrue("${low.name} < ${high.name} events", low.maxCachedEvents < high.maxCachedEvents)
        }
        assertEquals(0L, CachePreset.ULTRA_LIGHT.videoDiskBytes)
    }

    @Test
    fun canonicalKeyPreservesProxyParamsAndStripsTrackingAndFragment() {
        val proxyA = "https://proxy.example/img?url=https%3A%2F%2Fa.com%2F1.png&v=2&utm_source=x#top"
        val proxyB = "https://proxy.example/img?url=https%3A%2F%2Fb.com%2F2.png&v=2"
        assertNotEquals(
            CacheKeyPolicy.avatarDiskKey(proxyA),
            CacheKeyPolicy.avatarDiskKey(proxyB),
        )
        assertEquals(
            "avatar:https://proxy.example/img?url=https%3A%2F%2Fa.com%2F1.png&v=2",
            CacheKeyPolicy.avatarDiskKey(proxyA),
        )
        assertEquals(
            "avatar:https://cdn.example/pic.webp",
            CacheKeyPolicy.avatarDiskKey("https://cdn.example/pic.webp?utm_medium=social&fbclid=123#frag"),
        )
    }

    @Test
    fun avatarMemoryKeyDifferentiatesQualityModeAndAnimationFlag() {
        val url = "https://blossom.example/abc123.gif"
        val lowAnim = CacheKeyPolicy.avatarMemoryKey(url, AvatarMode.LOW, animated = true)
        val regAnim = CacheKeyPolicy.avatarMemoryKey(url, AvatarMode.REGULAR, animated = true)
        val regStill = CacheKeyPolicy.avatarMemoryKey(url, AvatarMode.REGULAR, animated = false)

        assertNotEquals(lowAnim, regAnim)
        assertNotEquals(regAnim, regStill)
        assertEquals(CacheKeyPolicy.avatarDiskKey(url), CacheKeyPolicy.avatarDiskKey("$url#preview"))
    }

    @Test
    fun mediaDiskKeyIsSharedAcrossFeedViewerAndGalleryWhileMemoryKeysStayIsolated() {
        val url = "https://blossom.example/deadbeef.jpg"
        val disk = CacheKeyPolicy.mediaDiskKey(url)
        assertEquals(disk, CacheKeyPolicy.mediaDiskKey("$url#zoom"))

        val feedLow = CacheKeyPolicy.mediaMemoryKey(url, CacheKeyPolicy.VARIANT_FEED_LOW)
        val feedFull = CacheKeyPolicy.mediaMemoryKey(url, CacheKeyPolicy.VARIANT_FEED_FULL)
        val viewer = CacheKeyPolicy.mediaMemoryKey(url, CacheKeyPolicy.VARIANT_VIEWER)
        val thumb = CacheKeyPolicy.mediaMemoryKey(url, CacheKeyPolicy.VARIANT_GALLERY_THUMB)

        assertEquals(4, setOf(feedLow, feedFull, viewer, thumb).size)
    }

    @Test
    fun avatarNormalizerSkipsGifsAndSvgs() {
        assertFalse(AvatarThumbnailNormalizer.shouldNormalizeContentType("image/gif", "https://x.com/a.gif"))
        assertFalse(AvatarThumbnailNormalizer.shouldNormalizeContentType("image/png", "https://x.com/a.gif?v=1"))
        assertFalse(AvatarThumbnailNormalizer.shouldNormalizeContentType("image/svg+xml", "https://x.com/a.svg"))
        assertTrue(AvatarThumbnailNormalizer.shouldNormalizeContentType("image/jpeg", "https://x.com/a.jpg"))
        assertTrue(AvatarThumbnailNormalizer.shouldNormalizeContentType(null, "https://x.com/hash123"))

        assertTrue(AvatarThumbnailNormalizer.isGifHeader("GIF89a...".toByteArray()))
        assertTrue(AvatarThumbnailNormalizer.isGifHeader("GIF87a...".toByteArray()))
        assertFalse(AvatarThumbnailNormalizer.isGifHeader(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0)))
    }

    @Test
    fun negativeLookupBackoffScalesUpAndCaps() {
        assertEquals(60_000L, NegativeLookupEntry.cooldownForAttempt(1))
        assertEquals(5 * 60_000L, NegativeLookupEntry.cooldownForAttempt(2))
        assertEquals(30 * 60_000L, NegativeLookupEntry.cooldownForAttempt(3))
        assertEquals(2 * 60 * 60_000L, NegativeLookupEntry.cooldownForAttempt(4))
        assertEquals(2 * 60 * 60_000L, NegativeLookupEntry.cooldownForAttempt(10))

        val entry = NegativeLookupEntry(lastAttemptMs = 1_000L, attempts = 1)
        assertTrue(entry.isCoolingDown(30_000L))
        assertFalse(entry.isCoolingDown(61_000L))
    }

    @Test
    fun engagementCacheMergesLiveReactorsSmoothlyWithoutDoubleCounting() {
        val me = "a".repeat(64)
        val seeded = EngagementCacheRepository.buildCachedReactorSet(
            cachedCount = 3,
            likedByMe = true,
            activePubkey = me,
        )
        assertEquals(3, seeded.size)
        assertTrue(me in seeded)

        // Own pubkey arrives from relay -> unchanged size (3).
        val afterSelf = EngagementCacheRepository.mergeRealReactor(seeded, me)
        assertEquals(3, afterSelf.size)

        // Two other real pubkeys arrive -> replace the two sentinels, size stays steady at 3.
        val afterP1 = EngagementCacheRepository.mergeRealReactor(afterSelf, "b".repeat(64))
        assertEquals(3, afterP1.size)
        val afterP2 = EngagementCacheRepository.mergeRealReactor(afterP1, "c".repeat(64))
        assertEquals(3, afterP2.size)
        assertTrue(afterP2.none { EngagementCacheRepository.isCachedSentinel(it) })

        // A brand-new 4th real like arrives -> increments smoothly to 4.
        val afterP3 = EngagementCacheRepository.mergeRealReactor(afterP2, "d".repeat(64))
        assertEquals(4, afterP3.size)
    }

    @Test
    fun likePatternEscapingProtectsSqlWildcards() {
        assertEquals("foo\\%bar\\_baz\\\\qux", ProfileRepository.escapeLikePattern("foo%bar_baz\\qux"))
    }

    @Test
    fun byteAndBudgetFormattingAreReadable() {
        assertEquals("off", CacheStorageManager.formatBudget(0L))
        assertEquals("64 MB", CacheStorageManager.formatBudget(64L * 1024 * 1024))
        assertEquals("1.5 GB", CacheStorageManager.formatBudget(1_536L * 1024 * 1024))
        assertEquals("1 GB", CacheStorageManager.formatBudget(1_024L * 1024 * 1024))
        assertEquals("512 B", CacheStorageManager.formatBytes(512L))
        assertEquals("24 KB", CacheStorageManager.formatBytes(24L * 1024))
    }
}
