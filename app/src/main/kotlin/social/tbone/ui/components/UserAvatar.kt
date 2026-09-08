package social.tbone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.decode.GifDecoder
import coil.request.ImageRequest
import coil.size.Precision
import social.tbone.nostr.ProfileContent
import social.tbone.settings.AvatarMode
import social.tbone.ui.media.LocalAnimatedAvatars
import social.tbone.ui.media.LocalAvatarMode
import social.tbone.ui.theme.BonyColors

/** Decode cap for LOW avatars — small, fast, light. */
private const val AVATAR_LOW_SIZE = 64

/** Decode cap for REGULAR avatars — plenty for a phone-width list. */
private const val AVATAR_REGULAR_SIZE = 256

/**
 * An avatar for a user in content (feed, threads, notifications, profiles).
 *
 * Honors the Settings → AVATARS preferences:
 *  - INITIAL   → deterministic initial square (the original Bony look, no network)
 *  - LOW       → the user's real profile picture, decoded at ≤64px
 *  - REGULAR   → the user's real profile picture at full quality
 *  - animated  → GIF avatars animate; when off, GIFs render as a still picture
 *
 * Always falls back to the initial square while loading or on error, so the
 * layout never breaks. Square with a hairline border, matching the design
 * system (never circular).
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
    val request = remember(picture, mode, animated) {
        ImageRequest.Builder(context)
            .data(picture)
            .crossfade(false)
            .apply {
                if (mode == AvatarMode.LOW) {
                    size(AVATAR_LOW_SIZE)
                    // Never upscale — decode at ≤64px for a fast, light render.
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

    Box(
        modifier = modifier
            .size(size)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt),
    ) {
        SubcomposeAsyncImage(
            model = request,
            contentDescription = profile?.bestName ?: "avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            loading = {
                DotAvatar(pubkeyHex = pubkeyHex, displayName = profile?.bestName, size = size)
            },
            error = {
                DotAvatar(pubkeyHex = pubkeyHex, displayName = profile?.bestName, size = size)
            },
        )
    }
}

private fun isGifUrl(url: String): Boolean =
    url.substringAfterLast('.').substringBefore('?').substringBefore('#').lowercase() == "gif"
