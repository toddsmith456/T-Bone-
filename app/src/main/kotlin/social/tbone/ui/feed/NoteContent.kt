package social.tbone.ui.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoPlaybackMode
import social.tbone.settings.VideoQuality
import social.tbone.ui.media.ImageViewer
import social.tbone.ui.media.LocalImageLoadMode
import social.tbone.ui.media.LocalVideoPlaybackMode
import social.tbone.ui.media.LocalVideoQuality
import social.tbone.ui.media.LocalVideoThumbnails
import social.tbone.ui.media.VideoViewerDialog
import social.tbone.ui.media.rememberVideoPlayer
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mov", "m4v", "mkv", "m3u8", "ogv", "3gp")
private val URL_REGEX = Regex("""https?://\S+""")

private val NOSTR_URI_REGEX = Regex("""nostr:(note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]*""")

/** Height of inline images in note cards — tall enough to actually see the picture. */
private val INLINE_IMAGE_HEIGHT = 260.dp

/** Height of the tap-to-open placeholder shown when inline loading is off. */
private val PLACEHOLDER_HEIGHT = 200.dp

/**
 * Cap for full-quality inline images. Coil down-samples to fit this box, which
 * keeps huge uploads from being decoded at full size in a phone-width card.
 * Tapping the image opens the full-resolution original in the in-app viewer.
 */
private const val FULL_QUALITY_MAX_SIZE = 1600

/** Cap for low-quality inline images — smaller decode, less memory, faster. */
private const val LOW_QUALITY_MAX_SIZE = 512

sealed class MediaItem {
    data class Image(val url: String) : MediaItem()
    data class Video(val url: String) : MediaItem()
}

data class ParsedContent(
    val text: String,
    val mediaItems: List<MediaItem>,
)

fun extractInlineQuoteId(content: String): String? =
    NOSTR_URI_REGEX.find(content)
        ?.value
        ?.let { Nip19.nostrUriToEventId(it) }

private val QUOTE_URI_REGEX = Regex("""nostr:(note1|nevent1|naddr1)[a-z0-9]+""")

/**
 * Removes the quote reference URI(s) from a note's body text.
 *
 * When a note quotes another note we render the quoted note as a card below
 * the body — so the raw `nostr:nevent1…` / `nostr:note1…` string must never
 * also appear inside the text (it looks like a broken link). Removes:
 *  1. any trailing quote URI block (the classic `…\n\nnostr:note1…` form), and
 *  2. any URI whose decoded event id equals [quotedId].
 * Legitimate inline note references that aren't the quote are left alone.
 */
fun stripQuoteRefs(content: String, quotedId: String): String {
    var out = content
    // 1. Trailing quote block (URI preceded by whitespace/newlines, at the end).
    out = out.replace(Regex("""(?:\s*nostr:(note1|nevent1|naddr1)[a-z0-9]+)+\s*$"""), "")
    // 2. The specific quoted event's URI anywhere in the text.
    out = QUOTE_URI_REGEX.replace(out) { m ->
        val id = Nip19.nostrUriToEventId(m.value)
        if (id == quotedId) "" else m.value
    }
    return out.trim().replace(Regex("\n{3,}"), "\n\n")
}

fun parseNoteContent(
    content: String,
    profiles: Map<String, ProfileContent> = emptyMap(),
): ParsedContent {
    val mediaItems = mutableListOf<MediaItem>()

    // Nostr URIs (npub/nprofile/note/nevent) are kept in the text so the rich
    // body renderer can make them tappable (@name → profile, note → thread).
    val withoutNostrUris = content

    val text = URL_REGEX.replace(withoutNostrUris) { match ->
        val url = match.value
        val ext = url.substringAfterLast('.').lowercase()
            .substringBefore('?').substringBefore('#')
        when {
            ext in IMAGE_EXTENSIONS -> { mediaItems.add(MediaItem.Image(url)); "" }
            ext in VIDEO_EXTENSIONS -> { mediaItems.add(MediaItem.Video(url)); "" }
            else -> match.value
        }
    }.replace(Regex(" {2,}"), " ").replace(Regex("\n{3,}"), "\n\n").trim()

    return ParsedContent(text, mediaItems)
}

private fun abbreviateNpub(hex: String): String =
    Nip19.hexToNpub(hex).let { "${it.take(9)}…${it.takeLast(4)}" }

@Composable
fun NoteMediaContent(mediaItems: List<MediaItem>, modifier: Modifier = Modifier) {
    if (mediaItems.isEmpty()) return
    val imageMode = LocalImageLoadMode.current
    val videoQuality = LocalVideoQuality.current
    val playbackMode = LocalVideoPlaybackMode.current
    val showVideoThumbnails = LocalVideoThumbnails.current
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    var videoViewerUrl by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mediaItems.forEach { item ->
            when (item) {
                is MediaItem.Image -> when (imageMode) {
                    ImageLoadMode.OFF ->
                        MediaPlaceholder(label = "[ image · tap to expand ]", url = item.url, onOpen = { imageViewerUrl = item.url })
                    ImageLoadMode.ON ->
                        InlineImage(url = item.url, lowQuality = false, onOpen = { imageViewerUrl = item.url })
                    ImageLoadMode.LOW_QUALITY ->
                        InlineImage(url = item.url, lowQuality = true, onOpen = { imageViewerUrl = item.url })
                }
                is MediaItem.Video -> {
                    val open = { videoViewerUrl = item.url }
                    when {
                        videoQuality == VideoQuality.OFF ->
                            MediaPlaceholder(
                                label = "[ video · tap to play ]",
                                url = item.url,
                                onOpen = open,
                            )
                        playbackMode == VideoPlaybackMode.LOAD_ON_TAP -> {
                            if (showVideoThumbnails) {
                                VideoThumbnail(url = item.url, onOpen = open)
                            } else {
                                MediaPlaceholder(
                                    label = "[ video · tap to play ]",
                                    url = item.url,
                                    onOpen = open,
                                )
                            }
                        }
                        else -> InlineVideo(
                            url = item.url,
                            quality = videoQuality,
                            playWhenReady = playbackMode == VideoPlaybackMode.PLAY_IMMEDIATELY && videoViewerUrl == null,
                            onOpen = open,
                        )
                    }
                }
            }
        }
    }

    imageViewerUrl?.let { url ->
        Dialog(
            onDismissRequest = { imageViewerUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ImageViewer(url = url, onClose = { imageViewerUrl = null })
        }
    }
    videoViewerUrl?.let { url ->
        VideoViewerDialog(
            url = url,
            // Off means no inline request; a deliberate tap still plays the
            // original in the full-screen viewer.
            quality = if (videoQuality == VideoQuality.OFF) VideoQuality.REGULAR else videoQuality,
            onClose = { videoViewerUrl = null },
        )
    }
}

/** Inline playback surface. A tap always promotes it to the themed player. */
@Composable
private fun InlineVideo(
    url: String,
    quality: VideoQuality,
    playWhenReady: Boolean,
    onOpen: () -> Unit,
) {
    val player = rememberVideoPlayer(url, quality, playWhenReady)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(INLINE_IMAGE_HEIGHT)
            .border(1.dp, BonyColors.Rule)
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { context ->
                androidx.media3.ui.PlayerView(context).apply {
                    this.player = player
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        // Keep the image-like gesture: the inline surface is a preview, while
        // all real controls are in the full-screen T-Bone player.
        Box(Modifier.fillMaxSize().clickable(onClick = onOpen))
    }
}

/** Video frame preview used only for the explicit load-on-tap thumbnail option. */
@Composable
private fun VideoThumbnail(url: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    SubcomposeAsyncImage(
        model = remember(url) {
            ImageRequest.Builder(context)
                .data(url)
                .size(LOW_QUALITY_MAX_SIZE)
                .crossfade(false)
                .build()
        },
        contentDescription = "video thumbnail",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxWidth()
            .height(INLINE_IMAGE_HEIGHT)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt)
            .clickable(onClick = onOpen),
        loading = { MediaStripes(Modifier.fillMaxSize()) },
        error = {
            MediaStripes(Modifier.fillMaxSize())
            Text(
                text = "[ video · tap to play ]",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .background(BonyColors.SurfaceAlt.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        },
    )
}

/**
 * Inline image rendered by Coil. Decodes at a bounded resolution so huge
 * uploads don't blow up memory; tapping opens the in-app viewer at the
 * original resolution (zoom, close, download — no browser).
 */
@Composable
private fun InlineImage(url: String, lowQuality: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val request = remember(url, lowQuality) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            .apply {
                if (lowQuality) {
                    size(LOW_QUALITY_MAX_SIZE)
                    // Never upscale — decode at ≤512px for a fast, light render.
                    precision(Precision.INEXACT)
                } else {
                    size(FULL_QUALITY_MAX_SIZE)
                }
            }
            .build()
    }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = "image",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(INLINE_IMAGE_HEIGHT)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt)
            .clickable(onClick = onOpen),
        loading = { MediaStripes(Modifier.fillMaxSize()) },
        error = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MediaStripes(Modifier.fillMaxSize())
                Text(
                    text = "[ image · tap to expand ]",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .background(BonyColors.SurfaceAlt.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
    )
}

/** Diagonal stripe placeholder matching the design system media block spec. */
@Composable
private fun MediaPlaceholder(label: String, url: String, onOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLACEHOLDER_HEIGHT)
            .border(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt)
            .clickable(onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        MediaStripes(Modifier.matchParentSize())

        Text(
            text = label,
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier
                .background(BonyColors.SurfaceAlt.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** The diagonal accent-stripe pattern used behind media placeholders. */
@Composable
private fun MediaStripes(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stripeWidth = 8.dp.toPx()
        val stripeGap = 8.dp.toPx()
        val pitch = stripeWidth + stripeGap
        val stripeColor = BonyColors.Rule
        val diagLen = (size.width + size.height) * 1.5f
        clipRect {
            var offset = -diagLen
            while (offset < diagLen) {
                drawLine(
                    color = stripeColor,
                    start = Offset(offset, 0f),
                    end = Offset(offset + size.height, size.height),
                    strokeWidth = stripeWidth,
                )
                offset += pitch
            }
        }
    }
}
