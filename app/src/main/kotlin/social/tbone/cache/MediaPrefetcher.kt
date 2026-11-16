package social.tbone.cache

import android.content.Context
import android.util.LruCache
import coil.decode.GifDecoder
import coil.request.ImageRequest
import coil.size.Precision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import social.tbone.ai.AiCoilParams
import social.tbone.ai.ScanSurface
import social.tbone.nostr.Event
import social.tbone.profile.ProfileRepository
import social.tbone.settings.AppSettings
import social.tbone.settings.AvatarMode
import social.tbone.settings.ImageLoadMode
import social.tbone.ui.feed.FeedListItem
import social.tbone.ui.feed.MediaItem
import social.tbone.ui.feed.parseNoteContent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tier-aware background prefetcher for avatars and inline feed media.
 *
 * Privacy & bandwidth guardrails:
 *  - **Tor privacy rule:** Speculative background prefetching is automatically
 *    paused when Tor is enabled so T-Bone never makes unsolicited third-party
 *    HTTP requests for posts the user hasn't actually scrolled into view.
 *  - Respects [AvatarMode] (`INITIAL` never fetches avatars) and [ImageLoadMode]
 *    (`OFF` never fetches feed images).
 *  - Uses the exact same [CacheKeyPolicy] keys and [AiCoilParams] as the UI
 *    composables so prefetched items hit RAM/disk with zero wasted decodes and
 *    respect offline AI NSFW filtering.
 */
@Singleton
class MediaPrefetcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val imageLoaders: ImageLoaderRegistry,
    private val profileRepository: ProfileRepository,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
            Timber.w(t, "MediaPrefetcher error")
        },
    )

    // Session dedup guard: never enqueue the same URL twice in one session.
    private val prefetchedAvatars = LruCache<String, Unit>(4_096)
    private val prefetchedMedia = LruCache<String, Unit>(2_048)

    init {
        // Warm updated avatar URLs when SWR discovers a followed user changed their picture.
        scope.launch {
            profileRepository.updatedAvatarUrls.collect { (pubkey, url) ->
                if (!canSpeculativelyPrefetch()) return@collect
                if (appSettings.avatarMode.value == AvatarMode.INITIAL) return@collect
                enqueueAvatar(pubkey, url, force = true)
            }
        }
    }

    /**
     * Quietly warms cached followed-user avatars in the background on tiers
     * where `preset.prefetchFollowAvatars` is enabled (Balanced / Performance / Turbo).
     */
    fun prefetchFollowAvatars(pubkeys: Collection<String>) {
        if (pubkeys.isEmpty()) return
        val preset = appSettings.cachePreset.value
        if (!preset.prefetchFollowAvatars) return
        if (!canSpeculativelyPrefetch()) return
        if (appSettings.avatarMode.value == AvatarMode.INITIAL) return

        scope.launch {
            var count = 0
            for (pk in pubkeys) {
                if (count >= MAX_FOLLOW_WARMUP) break
                val url = profileRepository.getAvatarUrl(pk) ?: continue
                if (enqueueAvatar(pk, url)) count++
            }
        }
    }

    /**
     * Preloads avatars (and on Performance/Turbo, feed photos) for items just
     * ahead of the current `LazyColumn` viewport.
     */
    fun prefetchFeedLookahead(items: List<FeedListItem>, visibleRange: IntRange) {
        if (items.isEmpty()) return
        val preset = appSettings.cachePreset.value
        val avatarAhead = preset.prefetchViewportAvatars
        val imageAhead = preset.prefetchViewportImages
        if (avatarAhead <= 0 && imageAhead <= 0) return
        if (!canSpeculativelyPrefetch()) return

        val avatarMode = appSettings.avatarMode.value
        val imageMode = appSettings.imageLoadMode.value
        val start = (visibleRange.last + 1).coerceAtLeast(0)
        if (start > items.lastIndex) return

        if (avatarAhead > 0 && avatarMode != AvatarMode.INITIAL) {
            val end = (visibleRange.last + avatarAhead).coerceAtMost(items.lastIndex)
            for (i in start..end) {
                val note = items[i] as? FeedListItem.Note ?: continue
                val url = profileRepository.getAvatarUrl(note.event.pubkey) ?: continue
                enqueueAvatar(note.event.pubkey, url)
            }
        }

        if (imageAhead > 0 && imageMode != ImageLoadMode.OFF) {
            val end = (visibleRange.last + imageAhead).coerceAtMost(items.lastIndex)
            val lowQuality = imageMode == ImageLoadMode.LOW
            for (i in start..end) {
                val note = items[i] as? FeedListItem.Note ?: continue
                enqueueFirstFeedImage(note.event, lowQuality)
            }
        }
    }

    fun clearSessionDedup() {
        prefetchedAvatars.evictAll()
        prefetchedMedia.evictAll()
    }

    private fun canSpeculativelyPrefetch(): Boolean =
        appSettings.initialized.value && !appSettings.torEnabled.value

    private fun enqueueAvatar(pubkey: String, url: String, force: Boolean = false): Boolean {
        val mode = appSettings.avatarMode.value
        if (mode == AvatarMode.INITIAL) return false
        val animated = appSettings.avatarAnimated.value
        val diskKey = CacheKeyPolicy.avatarDiskKey(url)
        val memKey = CacheKeyPolicy.avatarMemoryKey(url, mode, animated)
        if (!force && prefetchedAvatars.get(memKey) != null) return false
        prefetchedAvatars.put(memKey, Unit)

        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(memKey)
            .diskCacheKey(diskKey)
            .setParameter(AiCoilParams.SURFACE, ScanSurface.AVATAR.name, memoryCacheKey = null)
            .setParameter(AiCoilParams.AUTHOR_PUBKEY, pubkey, memoryCacheKey = null)
            .apply {
                if (mode == AvatarMode.LOW) {
                    size(64)
                    precision(Precision.INEXACT)
                } else {
                    size(256)
                }
                if (!animated && url.substringAfterLast('.').substringBefore('?').substringBefore('#').equals("gif", ignoreCase = true)) {
                    decoderFactory(GifDecoder.Factory(false))
                }
            }
            .build()

        imageLoaders.avatarLoader.enqueue(request)
        return true
    }

    private fun enqueueFirstFeedImage(event: Event, lowQuality: Boolean) {
        val parsed = runCatching { parseNoteContent(event.content) }.getOrNull() ?: return
        val firstImage = parsed.mediaItems.firstOrNull { it is MediaItem.Image } as? MediaItem.Image ?: return
        val url = firstImage.url
        val diskKey = CacheKeyPolicy.mediaDiskKey(url)
        val variant = if (lowQuality) CacheKeyPolicy.VARIANT_FEED_LOW else CacheKeyPolicy.VARIANT_FEED_FULL
        val memKey = CacheKeyPolicy.mediaMemoryKey(url, variant)
        if (prefetchedMedia.get(memKey) != null) return
        prefetchedMedia.put(memKey, Unit)

        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(memKey)
            .diskCacheKey(diskKey)
            .setParameter(AiCoilParams.SURFACE, ScanSurface.FEED_MEDIA.name, memoryCacheKey = null)
            .setParameter(AiCoilParams.AUTHOR_PUBKEY, event.pubkey, memoryCacheKey = null)
            .apply {
                firstImage.meta?.sha256?.let { sha ->
                    setParameter(AiCoilParams.IMETA_SHA256, sha, memoryCacheKey = null)
                }
                if (lowQuality) {
                    size(512)
                    precision(Precision.INEXACT)
                } else {
                    size(1600)
                }
            }
            .build()

        imageLoaders.mediaLoader.enqueue(request)
    }

    companion object {
        private const val MAX_FOLLOW_WARMUP = 120
    }
}
