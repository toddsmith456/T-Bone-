package social.tbone.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.tbone.db.EventDao
import social.tbone.db.EventRepository
import social.tbone.db.MediaScanCacheDao
import social.tbone.db.ProfileDao
import social.tbone.profile.ProfileRepository
import social.tbone.reactions.EngagementCacheRepository
import social.tbone.util.CacheCleaner
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Live on-disk storage breakdown displayed in Settings → Storage & Cache. */
data class CacheStorageStats(
    val audioDetailsBytes: Long = 0L,
    val avatarBytes: Long = 0L,
    val mediaBytes: Long = 0L,
    val videoBytes: Long = 0L,
    val tempBytes: Long = 0L,
    val databaseBytes: Long = 0L,
    val profileCount: Int = 0,
    val eventCount: Int = 0,
    val aiScanCount: Int = 0,
) {
    val totalCacheBytes: Long
        get() = avatarBytes + mediaBytes + videoBytes + tempBytes + audioDetailsBytes
}

/**
 * Measures real on-disk cache usage per bucket and exposes granular clear actions
 * so users can wipe heavy feed photos or videos without deleting their avatars
 * or followed profiles.
 */
@Singleton
class CacheStorageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioMetadataCache: social.tbone.media.audio.AudioMetadataCache,
    private val imageLoaders: ImageLoaderRegistry,
    private val mediaCache: MediaCacheManager,
    private val prefetcher: MediaPrefetcher,
    private val profileRepository: ProfileRepository,
    private val eventRepository: EventRepository,
    private val engagementCache: EngagementCacheRepository,
    private val profileDao: ProfileDao,
    private val eventDao: EventDao,
    private val mediaScanCacheDao: MediaScanCacheDao,
) {

    suspend fun measure(): CacheStorageStats = withContext(Dispatchers.IO) {
        val avatarDir = CacheDirectories.avatarDir(context)
        val mediaDir = CacheDirectories.mediaDir(context)
        val exoDir = CacheDirectories.exoMediaDir(context)

        val avatarBytes = dirSizeBytes(avatarDir)
        val mediaBytes = dirSizeBytes(mediaDir)
        val videoBytes = dirSizeBytes(exoDir)

        var tempBytes = 0L
        context.cacheDir?.listFiles()?.forEach { child ->
            if (child.name !in CacheDirectories.MANAGED_CACHE_DIRS) {
                tempBytes += dirSizeBytes(child)
            }
        }

        val dbFile = context.getDatabasePath("tbone.db")
        val dbBytes = listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm"),
        ).sumOf { if (it.exists()) it.length() else 0L }

        val profiles = runCatching { profileDao.count() }.getOrDefault(0)
        val events = runCatching { eventDao.count() }.getOrDefault(0)
        val aiScans = runCatching { mediaScanCacheDao.count() }.getOrDefault(0)

        CacheStorageStats(
            audioDetailsBytes = dirSizeBytes(File(context.cacheDir, CacheDirectories.AUDIO_DETAILS_CACHE)),
            avatarBytes = avatarBytes,
            mediaBytes = mediaBytes,
            videoBytes = videoBytes,
            tempBytes = tempBytes,
            databaseBytes = dbBytes,
            profileCount = profiles,
            eventCount = events,
            aiScanCount = aiScans,
        )
    }

    /** Clears only feed photos/banners/posters — keeps avatars intact. */
    suspend fun clearFeedMedia(): CacheStorageStats = withContext(Dispatchers.IO) {
        audioMetadataCache.clear()
        imageLoaders.clearMediaCache()
        prefetcher.clearSessionDedup()
        measure()
    }

    /** Clears only cached videos and audio clips — keeps avatars intact. */
    suspend fun clearVideoAudio(): CacheStorageStats = withContext(Dispatchers.IO) {
        mediaCache.clearCache()
        measure()
    }

    /** Clears only the dedicated avatar cache. */
    suspend fun clearAvatars(): CacheStorageStats = withContext(Dispatchers.IO) {
        imageLoaders.clearAvatarCache()
        prefetcher.clearSessionDedup()
        measure()
    }

    /** Clears feed photos, videos/audio, avatars, temps, engagement cache, and unfollowed profiles. */
    suspend fun clearAllCaches(): CacheStorageStats = withContext(Dispatchers.IO) {
        audioMetadataCache.clear()
        imageLoaders.clearMediaCache()
        imageLoaders.clearAvatarCache()
        mediaCache.clearCache()
        prefetcher.clearSessionDedup()
        runCatching { CacheCleaner.cleanup(context) }
        runCatching { engagementCache.clearAll() }
        runCatching { profileRepository.clearUnfollowedCache() }
        runCatching { eventRepository.clearAll() }
        measure()
    }

    companion object {
        internal fun dirSizeBytes(file: File?): Long {
            if (file == null || !file.exists()) return 0L
            if (file.isFile) return file.length()
            var total = 0L
            file.listFiles()?.forEach { child ->
                total += dirSizeBytes(child)
            }
            return total
        }

        fun formatBytes(bytes: Long): String {
            val b = bytes.coerceAtLeast(0L)
            return when {
                b >= 1_073_741_824L -> "%.2f GB".format(java.util.Locale.US, b / 1_073_741_824.0)
                b >= 1_048_576L -> "%.1f MB".format(java.util.Locale.US, b / 1_048_576.0)
                b >= 1_024L -> "%d KB".format(java.util.Locale.US, b / 1_024L)
                else -> "$b B"
            }
        }

        fun formatBudget(bytes: Long): String {
            if (bytes <= 0L) return "off"
            return when {
                bytes >= 1_073_741_824L -> {
                    val gb = bytes / 1_073_741_824.0
                    if (gb == gb.toLong().toDouble()) "${gb.toLong()} GB" else "%.1f GB".format(java.util.Locale.US, gb)
                }
                else -> "${bytes / (1024 * 1024)} MB"
            }
        }
    }
}
