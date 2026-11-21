package social.tbone.cache

import android.content.Context
import java.io.File

/**
 * Canonical cache subdirectories under [Context.getCacheDir].
 *
 * Keeping every cache folder inside `cacheDir` guarantees Android's storage
 * accounting works accurately AND T-Bone's duress PIN [social.tbone.security.DataWiper]
 * removes 100% of cached bytes automatically.
 */
object CacheDirectories {
    /** Dedicated isolated LRU cache for normalized profile avatars. */
    const val AVATAR_CACHE = "avatar_cache"

    /** Feed images, banners, gallery photos, and posters (preserved directory name). */
    const val MEDIA_CACHE = "image_cache"

    /** Media3 SimpleCache disk cache for video and audio playback. */
    const val EXO_MEDIA_CACHE = "exo_media_cache"

    const val AUDIO_DETAILS_CACHE = "audio_details_cache"

    /** Directories managed by their own LRU evictors (skipped by CacheCleaner temp sweep). */
    val MANAGED_CACHE_DIRS: Set<String> = setOf(
        AVATAR_CACHE,
        MEDIA_CACHE,
        EXO_MEDIA_CACHE,
        AUDIO_DETAILS_CACHE,
    )

    fun avatarDir(context: Context): File = File(context.cacheDir, AVATAR_CACHE)
    fun mediaDir(context: Context): File = File(context.cacheDir, MEDIA_CACHE)
    fun exoMediaDir(context: Context): File = File(context.cacheDir, EXO_MEDIA_CACHE)
}
