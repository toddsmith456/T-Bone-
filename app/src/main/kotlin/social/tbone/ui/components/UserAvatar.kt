package social.tbone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.size.Precision
import social.tbone.ai.AiCoilParams
import social.tbone.ai.ScanSurface
import social.tbone.cache.CacheKeyPolicy
import social.tbone.cache.ImageLoaderRegistry
import social.tbone.nostr.ProfileContent
import social.tbone.settings.AvatarMode
import social.tbone.settings.LocalContentFilter
import social.tbone.ui.media.LocalAnimatedAvatars
import social.tbone.ui.media.LocalAvatarMode
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.appCornerShape

/** Decode cap for LOW avatars — small, fast, light. */
private const val AVATAR_LOW_SIZE = 64

/** Decode cap for REGULAR avatars — plenty for a phone-width list. */
private const val AVATAR_REGULAR_SIZE = 256

/**
 * An avatar for a user in content (feed, threads, notifications, profiles).
 *
 * Uses T-Bone's dedicated isolated `avatar_cache` loader so heavy feed media
 * can never evict profile pictures, and checks RAM synchronously on frame 0 so
 * cached avatars never flash `DotAvatar` while scrolling.
 */
@Composable
fun UserAvatar(
    pubkeyHex: String,
    profile: ProfileContent?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val mode = LocalAvatarMode.current
    val animated = LocalAnimatedAvatars.current
    val picture = profile?.picture?.takeIf { it.isNotBlank() }

    if (mode == AvatarMode.INITIAL || picture == null) {
        DotAvatar(
            pubkeyHex = pubkeyHex,
            displayName = profile?.bestName,
            size = size,
            modifier = modifier,
        )
        return
    }

    val context = LocalContext.current
    val avatarLoader = remember(context) { ImageLoaderRegistry.avatarImageLoader(context) }
    val filter = LocalContentFilter.current
    val aiAvatarKey = filter.aiFilterConfig.takeIf { filter.aiHasInstalledModel }
    val diskKey = remember(picture) { CacheKeyPolicy.avatarDiskKey(picture) }
    val memoryKey = remember(picture, mode, animated) {
        CacheKeyPolicy.avatarMemoryKey(picture, mode, animated)
    }
    val request = remember(picture, mode, animated, pubkeyHex, aiAvatarKey, diskKey, memoryKey) {
        ImageRequest.Builder(context)
            .data(picture)
            .memoryCacheKey(memoryKey)
            .diskCacheKey(diskKey)
            .setParameter(AiCoilParams.SURFACE, ScanSurface.AVATAR.name, memoryCacheKey = null)
            .setParameter(AiCoilParams.AUTHOR_PUBKEY, pubkeyHex, memoryCacheKey = null)
            .crossfade(false)
            .apply {
                if (mode == AvatarMode.LOW) {
                    size(AVATAR_LOW_SIZE)
                    precision(Precision.INEXACT)
                } else {
                    size(AVATAR_REGULAR_SIZE)
                }
                // "Show a picture instead of gif avatars": decode GIFs statically
                // (allowAnimation = false renders only the first frame).
                if (!animated && isGifUrl(picture)) {
                    decoderFactory(GifDecoder.Factory(false))
                }
            }
            .build()
    }

    // Synchronous RAM check eliminates frame-0 DotAvatar flicker on fast scroll.
    val inMemory = remember(avatarLoader, memoryKey) {
        avatarLoader.memoryCache?.get(MemoryCache.Key(memoryKey)) != null
    }
    val shape = appCornerShape(MaterialTheme.shapes.small)
    var imageLoaded by remember(request) { mutableStateOf(inMemory) }
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .border(1.dp, BonyColors.Rule, shape)
            .background(BonyColors.SurfaceAlt, shape),
    ) {
        if (!imageLoaded) {
            DotAvatar(pubkeyHex = pubkeyHex, displayName = profile?.bestName, size = size)
        }
        AsyncImage(
            model = request,
            imageLoader = avatarLoader,
            contentDescription = profile?.bestName ?: "avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onLoading = { if (!inMemory) imageLoaded = false },
            onSuccess = { imageLoaded = true },
            onError = { imageLoaded = false },
        )
    }
}

private fun isGifUrl(url: String): Boolean =
    url.substringAfterLast('.').substringBefore('?').substringBefore('#').lowercase() == "gif"
