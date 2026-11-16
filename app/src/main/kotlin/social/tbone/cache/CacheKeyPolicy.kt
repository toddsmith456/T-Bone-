package social.tbone.cache

import social.tbone.settings.AvatarMode

/**
 * Pure-Kotlin cache key normalization shared across avatars, inline feed media,
 * full-screen viewers, profile galleries, and background prefetchers.
 *
 * Guarantees:
 *  1. Query parameters that identify an image (proxy `?url=`, `?v=`, `?s=`, `?id=`)
 *     are preserved so image proxies never collide onto the wrong picture.
 *  2. Only known tracking parameters (`utm_*`, `fbclid`, `gclid`, etc.) and URL
 *     fragments (`#...`) are stripped.
 *  3. Feed inline images, fullscreen ImageViewer, and ProfileGallery share ONE
 *     canonical disk key so tapping an image never re-downloads it, while keeping
 *     distinct memory keys per decode size/variant.
 *  4. Avatar memory keys include [AvatarMode] and animation state so changing
 *     avatar settings takes effect immediately without serving stale RAM bitmaps.
 */
object CacheKeyPolicy {

    private val TRACKING_PARAM_NAMES = setOf(
        "fbclid",
        "gclid",
        "dclid",
        "msclkid",
        "mc_cid",
        "mc_eid",
        "igshid",
        "si",
    )

    /** Strips fragment and known tracking params while keeping meaningful query params intact. */
    fun canonicalUrlKey(rawUrl: String): String {
        val withoutFragment = rawUrl.trim().substringBefore('#')
        val queryIdx = withoutFragment.indexOf('?')
        if (queryIdx < 0) return withoutFragment

        val base = withoutFragment.substring(0, queryIdx)
        val rawQuery = withoutFragment.substring(queryIdx + 1)
        if (rawQuery.isEmpty()) return base

        val kept = rawQuery.split('&').filter { pair ->
            if (pair.isEmpty()) return@filter false
            val key = pair.substringBefore('=').lowercase()
            !key.startsWith("utm_") && key !in TRACKING_PARAM_NAMES
        }
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

    /** Dedicated disk cache key for avatars (stored inside `avatar_cache`). */
    fun avatarDiskKey(url: String): String = "avatar:${canonicalUrlKey(url)}"

    /**
     * Memory cache key for avatars. Includes [mode] (`low` vs `regular`) and
     * [animated] (`anim` vs `still`) so settings changes never serve a stale
     * 64px or static-frame bitmap from RAM.
     */
    fun avatarMemoryKey(url: String, mode: AvatarMode, animated: Boolean): String {
        val animTag = if (animated) "anim" else "still"
        return "avatar:${canonicalUrlKey(url)}|${mode.prefValue}|$animTag"
    }

    /**
     * Shared canonical disk key across feed inline images, fullscreen ImageViewer,
     * ProfileGallery thumbnails, and media prefetchers.
     */
    fun mediaDiskKey(url: String): String = "media:${canonicalUrlKey(url)}"

    /** Variant-specific RAM key so low-res feed bitmaps don't pollute full-screen zoom. */
    fun mediaMemoryKey(url: String, variant: String): String =
        "media:${canonicalUrlKey(url)}#$variant"

    const val VARIANT_FEED_LOW = "feed-low"
    const val VARIANT_FEED_FULL = "feed-full"
    const val VARIANT_VIEWER = "viewer"
    const val VARIANT_GALLERY_THUMB = "gallery-320"
    const val VARIANT_POSTER = "poster"
    const val VARIANT_BANNER = "banner"
}
