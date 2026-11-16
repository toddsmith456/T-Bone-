package social.tbone.cache

import android.content.ComponentCallbacks2
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Call
import social.tbone.ai.NsfwCoilInterceptor
import social.tbone.di.ImageClientProvider
import social.tbone.settings.CachePreset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns T-Bone's two isolated Coil [ImageLoader] pipelines:
 *
 *  1. **Avatar loader** (`cacheDir/avatar_cache`):
 *     - Dedicated LRU budget so heavy feed photos/GIFs can NEVER evict avatars.
 *     - Runs [AvatarThumbnailNormalizer] so multi-MB raw avatar photos are
 *       stored on disk as crisp ≤256×256 WebP thumbnails (~12–20 KB each).
 *     - Separate memory cache preserved longer under Android memory pressure.
 *
 *  2. **Feed / Media loader** (`cacheDir/image_cache`, installed as global Coil):
 *     - Handles inline feed images, full-screen ImageViewer, profile banners,
 *       gallery thumbnails, and video poster frames.
 *     - Uses `respectCacheHeaders(false)` so misconfigured Nostr/Blossom servers
 *       sending `no-cache` or `max-age=0` never force redundant downloads.
 *
 * Both loaders route 100% of HTTP traffic through [ImageClientProvider.client]
 * (strict Tor + [social.tbone.di.TorCallGuard] compliance) and register
 * [NsfwCoilInterceptor] for zero-FOUC offline AI filtering.
 */
@Singleton
class ImageLoaderRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nsfwCoilInterceptor: NsfwCoilInterceptor,
) {
    private val lock = Any()

    @Volatile
    private var installedPreset: CachePreset? = null

    @Volatile
    private var _mediaLoader: ImageLoader? = null

    @Volatile
    private var _avatarLoader: ImageLoader? = null

    private val mediaCallFactory = Call.Factory { request ->
        ImageClientProvider.client.newCall(request)
    }

    private val avatarCallFactory = AvatarThumbnailNormalizer.callFactory {
        ImageClientProvider.client
    }

    val mediaLoader: ImageLoader
        get() = _mediaLoader ?: ensureInitialized(readPersistedPreset()).first

    val avatarLoader: ImageLoader
        get() = _avatarLoader ?: ensureInitialized(readPersistedPreset()).second

    /**
     * Installs both loaders synchronously on frame 0 (called from `BonyApp.onCreate`).
     * Subsequent calls with the same preset are cheap no-ops.
     */
    fun initialize(preset: CachePreset) {
        synchronized(lock) {
            if (installedPreset == preset && _mediaLoader != null && _avatarLoader != null) return
            installLocked(preset)
        }
    }

    /** Rebuilds loaders when the user switches to a different [CachePreset]. */
    fun applyPreset(preset: CachePreset) {
        synchronized(lock) {
            if (installedPreset == preset && _mediaLoader != null && _avatarLoader != null) return
            persistPresetMirror(preset)
            installLocked(preset)
        }
    }

    private fun ensureInitialized(preset: CachePreset): Pair<ImageLoader, ImageLoader> =
        synchronized(lock) {
            val m = _mediaLoader
            val a = _avatarLoader
            if (m != null && a != null) {
                m to a
            } else {
                installLocked(preset)
            }
        }

    private fun installLocked(preset: CachePreset): Pair<ImageLoader, ImageLoader> {
        val prevMedia = _mediaLoader
        val prevAvatar = _avatarLoader

        val newMedia = buildMediaLoader(preset)
        val newAvatar = buildAvatarLoader(preset)

        _mediaLoader = newMedia
        _avatarLoader = newAvatar
        installedPreset = preset
        currentAvatarLoaderRef = newAvatar
        currentMediaLoaderRef = newMedia
        Coil.setImageLoader(newMedia)

        runCatching { prevMedia?.shutdown() }
        runCatching { prevAvatar?.shutdown() }

        return newMedia to newAvatar
    }

    private fun buildMediaLoader(preset: CachePreset): ImageLoader =
        ImageLoader.Builder(context)
            .callFactory(mediaCallFactory)
            .respectCacheHeaders(false)
            .components { add(nsfwCoilInterceptor) }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(preset.coilRamPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(CacheDirectories.mediaDir(context))
                    .maxSizeBytes(preset.mediaDiskBytes)
                    .build()
            }
            .build()

    private fun buildAvatarLoader(preset: CachePreset): ImageLoader =
        ImageLoader.Builder(context)
            .callFactory(avatarCallFactory)
            .respectCacheHeaders(false)
            .components { add(nsfwCoilInterceptor) }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(preset.avatarRamPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(CacheDirectories.avatarDir(context))
                    .maxSizeBytes(preset.avatarDiskBytes)
                    .build()
            }
            .build()

    /**
     * Proportional memory trimming: sheds large feed bitmaps first on moderate/low
     * memory pressure while keeping tiny decoded avatars warm in RAM across normal
     * background switches (UI_HIDDEN) unless Android signals critical/complete pressure.
     */
    fun onTrimMemory(level: Int) {
        when {
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                runCatching { _mediaLoader?.memoryCache?.clear() }
                runCatching { _avatarLoader?.memoryCache?.clear() }
            }
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                runCatching { _mediaLoader?.memoryCache?.trimMemory(level) }
                runCatching { _avatarLoader?.memoryCache?.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) }
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                runCatching { _mediaLoader?.memoryCache?.trimMemory(level) }
            }
        }
    }

    /** Clears only the feed/media image cache (avatars stay intact). */
    @OptIn(ExperimentalCoilApi::class)
    fun clearMediaCache() {
        runCatching { _mediaLoader?.memoryCache?.clear() }
        runCatching { _mediaLoader?.diskCache?.clear() }
        runCatching {
            val dir = CacheDirectories.mediaDir(context)
            if (dir.exists()) dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** Clears only the dedicated avatar cache (feed media stays intact). */
    @OptIn(ExperimentalCoilApi::class)
    fun clearAvatarCache() {
        runCatching { _avatarLoader?.memoryCache?.clear() }
        runCatching { _avatarLoader?.diskCache?.clear() }
        runCatching {
            val dir = CacheDirectories.avatarDir(context)
            if (dir.exists()) dir.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    /** Clears RAM caches immediately (used during duress wipe). */
    fun clearAllMemory() {
        runCatching { _mediaLoader?.memoryCache?.clear() }
        runCatching { _avatarLoader?.memoryCache?.clear() }
    }

    /** Evicts a blocked media item from BOTH loaders (offline AI HIDE / Parental PIN). */
    @OptIn(ExperimentalCoilApi::class)
    fun evictFromAll(memoryKey: MemoryCache.Key?, resultMemoryKey: MemoryCache.Key?, diskKey: String?) {
        val loaders = listOfNotNull(_mediaLoader, _avatarLoader)
        for (loader in loaders) {
            memoryKey?.let { runCatching { loader.memoryCache?.remove(it) } }
            resultMemoryKey?.let { runCatching { loader.memoryCache?.remove(it) } }
            if (!diskKey.isNullOrBlank()) {
                runCatching { loader.diskCache?.remove(diskKey) }
            }
        }
    }

    fun readPersistedPreset(): CachePreset {
        val prefs = context.getSharedPreferences(CachePreset.PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(CachePreset.PREFS_KEY_PRESET, null)
        val legacy = if (prefs.contains(CachePreset.PREFS_KEY_LEGACY_LIGHTWEIGHT)) {
            prefs.getBoolean(CachePreset.PREFS_KEY_LEGACY_LIGHTWEIGHT, false)
        } else {
            null
        }
        return CachePreset.fromPref(raw, legacy)
    }

    fun persistPresetMirror(preset: CachePreset) {
        context.getSharedPreferences(CachePreset.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(CachePreset.PREFS_KEY_PRESET, preset.prefValue)
            .putBoolean(CachePreset.PREFS_KEY_LEGACY_LIGHTWEIGHT, preset.isLightweightCompatible)
            .apply()
    }

    companion object {
        @Volatile
        private var currentAvatarLoaderRef: ImageLoader? = null

        @Volatile
        private var currentMediaLoaderRef: ImageLoader? = null

        /** Fast composable accessor for the dedicated avatar ImageLoader. */
        fun avatarImageLoader(context: Context): ImageLoader =
            currentAvatarLoaderRef ?: Coil.imageLoader(context)

        /** Fast accessor for the feed/media ImageLoader. */
        fun mediaImageLoader(context: Context): ImageLoader =
            currentMediaLoaderRef ?: Coil.imageLoader(context)

        /** Evicts from both active loaders when invoked from interceptors without DI cycle. */
        @OptIn(ExperimentalCoilApi::class)
        fun evictBlocked(
            context: Context,
            memoryKey: MemoryCache.Key?,
            resultMemoryKey: MemoryCache.Key?,
            diskKey: String?,
            evictDisk: Boolean,
        ) {
            val loaders = linkedSetOf<ImageLoader>()
            currentMediaLoaderRef?.let { loaders += it }
            currentAvatarLoaderRef?.let { loaders += it }
            if (loaders.isEmpty()) {
                runCatching { Coil.imageLoader(context) }.getOrNull()?.let { loaders += it }
            }
            for (loader in loaders) {
                memoryKey?.let { runCatching { loader.memoryCache?.remove(it) } }
                resultMemoryKey?.let { runCatching { loader.memoryCache?.remove(it) } }
                if (evictDisk && !diskKey.isNullOrBlank()) {
                    runCatching { loader.diskCache?.remove(diskKey) }
                }
            }
        }
    }
}
