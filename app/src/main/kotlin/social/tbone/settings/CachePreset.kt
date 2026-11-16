package social.tbone.settings

/**
 * Multi-tier storage & caching presets tailored to different device storage budgets.
 *
 * Every tier isolates avatars into their own protected disk cache so heavy feed
 * images/GIFs can never evict profile pictures, and keeps followed accounts
 * pinned in the local profile database.
 */
enum class CachePreset(
    val prefValue: String,
    val label: String,
    val shortLabel: String,
    val approxTotalLabel: String,
    val summary: String,
    val avatarDiskBytes: Long,
    val mediaDiskBytes: Long,
    val videoDiskBytes: Long,
    val maxCachedProfiles: Int,
    val profileRamLimit: Int,
    val maxCachedEvents: Int,
    val eventRamLimit: Int,
    val coilRamPercent: Double,
    val avatarRamPercent: Double,
    val profileSwrTtlMs: Long,
    val prefetchFollowAvatars: Boolean,
    val prefetchViewportAvatars: Int,
    val prefetchViewportImages: Int,
    val maxEngagementRows: Int,
) {
    ULTRA_LIGHT(
        prefValue = "ultra_light",
        label = "Ultra-Light",
        shortLabel = "light",
        approxTotalLabel = "~60 MB",
        summary = "15 MB avatars · 35 MB photos · video cache off · 1k profiles · minimal RAM",
        avatarDiskBytes = 15L * 1024 * 1024,
        mediaDiskBytes = 35L * 1024 * 1024,
        videoDiskBytes = 0L,
        maxCachedProfiles = 1_000,
        profileRamLimit = 500,
        maxCachedEvents = 1_000,
        eventRamLimit = 250,
        coilRamPercent = 0.12,
        avatarRamPercent = 0.04,
        profileSwrTtlMs = 24L * 60 * 60 * 1000,
        prefetchFollowAvatars = false,
        prefetchViewportAvatars = 0,
        prefetchViewportImages = 0,
        maxEngagementRows = 0,
    ),
    COMPACT(
        prefValue = "compact",
        label = "Compact",
        shortLabel = "compact",
        approxTotalLabel = "~150 MB",
        summary = "30 MB avatars · 80 MB photos · 30 MB video · 3k profiles · light avatar preload",
        avatarDiskBytes = 30L * 1024 * 1024,
        mediaDiskBytes = 80L * 1024 * 1024,
        videoDiskBytes = 30L * 1024 * 1024,
        maxCachedProfiles = 3_000,
        profileRamLimit = 1_000,
        maxCachedEvents = 2_500,
        eventRamLimit = 500,
        coilRamPercent = 0.18,
        avatarRamPercent = 0.06,
        profileSwrTtlMs = 12L * 60 * 60 * 1000,
        prefetchFollowAvatars = false,
        prefetchViewportAvatars = 5,
        prefetchViewportImages = 0,
        maxEngagementRows = 0,
    ),
    BALANCED(
        prefValue = "balanced",
        label = "Balanced",
        shortLabel = "balanced",
        approxTotalLabel = "~450 MB",
        summary = "64 MB avatars · 250 MB photos · 100 MB video · 10k profiles · follow avatar warm-up",
        avatarDiskBytes = 64L * 1024 * 1024,
        mediaDiskBytes = 250L * 1024 * 1024,
        videoDiskBytes = 100L * 1024 * 1024,
        maxCachedProfiles = 10_000,
        profileRamLimit = 2_000,
        maxCachedEvents = 5_000,
        eventRamLimit = 1_000,
        coilRamPercent = 0.25,
        avatarRamPercent = 0.08,
        profileSwrTtlMs = 6L * 60 * 60 * 1000,
        prefetchFollowAvatars = true,
        prefetchViewportAvatars = 10,
        prefetchViewportImages = 0,
        maxEngagementRows = 500,
    ),
    PERFORMANCE(
        prefValue = "performance",
        label = "Performance",
        shortLabel = "perf",
        approxTotalLabel = "~1.2 GB",
        summary = "150 MB avatars · 600 MB photos · 350 MB video · 25k profiles · avatar + photo preload",
        avatarDiskBytes = 150L * 1024 * 1024,
        mediaDiskBytes = 600L * 1024 * 1024,
        videoDiskBytes = 350L * 1024 * 1024,
        maxCachedProfiles = 25_000,
        profileRamLimit = 4_000,
        maxCachedEvents = 15_000,
        eventRamLimit = 2_000,
        coilRamPercent = 0.30,
        avatarRamPercent = 0.10,
        profileSwrTtlMs = 4L * 60 * 60 * 1000,
        prefetchFollowAvatars = true,
        prefetchViewportAvatars = 20,
        prefetchViewportImages = 6,
        maxEngagementRows = 2_000,
    ),
    TURBO(
        prefValue = "turbo",
        label = "Turbo",
        shortLabel = "turbo",
        approxTotalLabel = "~3.0 GB",
        summary = "300 MB avatars · 1.5 GB photos · 1 GB video · 75k profiles · max local speed",
        avatarDiskBytes = 300L * 1024 * 1024,
        mediaDiskBytes = 1_500L * 1024 * 1024,
        videoDiskBytes = 1_000L * 1024 * 1024,
        maxCachedProfiles = 75_000,
        profileRamLimit = 6_000,
        maxCachedEvents = 40_000,
        eventRamLimit = 4_000,
        coilRamPercent = 0.35,
        avatarRamPercent = 0.12,
        profileSwrTtlMs = 2L * 60 * 60 * 1000,
        prefetchFollowAvatars = true,
        prefetchViewportAvatars = 30,
        prefetchViewportImages = 12,
        maxEngagementRows = 5_000,
    );

    val isLightweightCompatible: Boolean
        get() = this == ULTRA_LIGHT || this == COMPACT

    companion object {
        const val PREFS_NAME = "tbone_cache_prefs"
        const val PREFS_KEY_PRESET = "cache_preset"
        const val PREFS_KEY_LEGACY_LIGHTWEIGHT = "image_cache_lightweight"

        /**
         * Resolves the active preset from DataStore/SharedPreferences, migrating
         * older installs that only stored the binary `image_cache_lightweight` flag.
         */
        fun fromPref(raw: String?, legacyLightweight: Boolean? = null): CachePreset {
            if (!raw.isNullOrBlank()) {
                entries.firstOrNull { it.prefValue.equals(raw.trim(), ignoreCase = true) }?.let { return it }
            }
            return if (legacyLightweight == true) COMPACT else BALANCED
        }
    }
}
