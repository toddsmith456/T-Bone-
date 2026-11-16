package social.tbone.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Call
import social.tbone.di.ImageClientProvider
import social.tbone.settings.CachePreset
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the shared Media3 [SimpleCache] (`cacheDir/exo_media_cache`) used by
 * both inline/fullscreen video players and the inline audio player.
 *
 * Benefits:
 *  - Replaying a video or audio clip reads straight from local disk (zero network).
 *  - Switching an inline feed video to fullscreen hands off via the same cache
 *    instead of re-streaming from byte 0.
 *  - Ultra-Light preset (`videoDiskBytes == 0`) bypasses disk caching cleanly.
 *  - Upstream always delegates dynamically to [ImageClientProvider.client],
 *    preserving strict Tor routing and [social.tbone.di.TorCallGuard].
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val activePlayers = AtomicInteger(0)

    private var simpleCache: SimpleCache? = null
    private var dbProvider: StandaloneDatabaseProvider? = null
    private var configuredBytes: Long = 0L

    @Volatile
    private var desiredBytes: Long = CachePreset.BALANCED.videoDiskBytes

    @Volatile
    private var pendingReconfigure: Boolean = false

    private val dynamicUpstreamFactory: DataSource.Factory =
        OkHttpDataSource.Factory(Call.Factory { request ->
            ImageClientProvider.client.newCall(request)
        })

    init {
        instanceRef = this
    }

    /** Updates the target video/audio disk cache cap from the active [CachePreset]. */
    fun applyPreset(preset: CachePreset) {
        synchronized(lock) {
            desiredBytes = preset.videoDiskBytes
            if (configuredBytes == desiredBytes && (desiredBytes == 0L || simpleCache != null)) {
                return
            }
            if (activePlayers.get() > 0) {
                // Avoid tearing down SimpleCache underneath an active ExoPlayer;
                // reconfigure automatically as soon as the last player releases.
                pendingReconfigure = true
                return
            }
            releaseLocked()
            if (desiredBytes == 0L) {
                deleteDirQuietly()
            } else {
                ensureCacheLocked(desiredBytes)
            }
        }
    }

    /**
     * Acquires a [DataSource.Factory] for a new ExoPlayer instance.
     * Callers MUST invoke [onPlayerReleased] in `DisposableEffect.onDispose`.
     */
    fun acquireDataSourceFactory(): DataSource.Factory {
        activePlayers.incrementAndGet()
        val cache = synchronized(lock) {
            val bytes = desiredBytes
            if (bytes <= 0L) null else ensureCacheLocked(bytes)
        }
        return if (cache != null) {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(dynamicUpstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        } else {
            dynamicUpstreamFactory
        }
    }

    /** Releases a player lease and applies any deferred preset reconfiguration. */
    fun onPlayerReleased() {
        val remaining = activePlayers.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0 && pendingReconfigure) {
            synchronized(lock) {
                if (activePlayers.get() == 0 && pendingReconfigure) {
                    pendingReconfigure = false
                    releaseLocked()
                    if (desiredBytes == 0L) {
                        deleteDirQuietly()
                    } else {
                        ensureCacheLocked(desiredBytes)
                    }
                }
            }
        }
    }

    /** Clears all cached video & audio bytes (Settings button or duress wipe). */
    fun clearCache() {
        synchronized(lock) {
            releaseLocked()
            deleteDirQuietly()
            // Re-open lazily on next playback so clearing doesn't recreate files right before a wipe.
        }
    }

    /** Releases file handles before duress wipe deletes `cacheDir`. */
    fun releaseForWipe() {
        synchronized(lock) {
            releaseLocked()
            deleteDirQuietly()
        }
    }

    private fun ensureCacheLocked(maxBytes: Long): SimpleCache? {
        if (maxBytes <= 0L) return null
        if (simpleCache != null && configuredBytes == maxBytes) return simpleCache
        releaseLocked()
        return runCatching {
            val dir = CacheDirectories.exoMediaDir(context).apply { mkdirs() }
            val provider = dbProvider ?: StandaloneDatabaseProvider(context).also { dbProvider = it }
            val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
            SimpleCache(dir, evictor, provider).also {
                simpleCache = it
                configuredBytes = maxBytes
            }
        }.onFailure { err ->
            Timber.w(err, "Failed initializing Media3 SimpleCache; falling back to direct streaming")
            releaseLocked()
        }.getOrNull()
    }

    private fun releaseLocked() {
        runCatching { simpleCache?.release() }
        simpleCache = null
        configuredBytes = 0L
    }

    private fun deleteDirQuietly() {
        runCatching {
            val dir = CacheDirectories.exoMediaDir(context)
            if (dir.exists()) dir.deleteRecursively()
        }
    }

    companion object {
        @Volatile
        private var instanceRef: MediaCacheManager? = null

        /** Returns a cached [DataSource.Factory] if initialized, or direct OkHttp streaming. */
        fun acquireFactory(): DataSource.Factory {
            val mgr = instanceRef
            return mgr?.acquireDataSourceFactory()
                ?: OkHttpDataSource.Factory(Call.Factory { request ->
                    ImageClientProvider.client.newCall(request)
                })
        }

        fun releaseLease() {
            instanceRef?.onPlayerReleased()
        }
    }
}
