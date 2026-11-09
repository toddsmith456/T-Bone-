package social.tbone.ui.feed

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerView
import android.view.LayoutInflater
import coil.decode.GifDecoder
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import social.tbone.ui.media.LocalMediaFitted
import social.tbone.ui.media.LocalVideoThumbnails
import social.tbone.util.BlurHash
import coil.request.ImageRequest
import coil.size.Precision
import social.tbone.R
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.settings.ImageLoadMode
import social.tbone.settings.VideoQuality
import social.tbone.ui.media.ImageViewer
import social.tbone.ui.media.InlineAudioPlayer
import social.tbone.ui.media.LocalImageLoadMode
import social.tbone.ui.media.LocalVideoQuality
import social.tbone.ui.media.VideoViewerDialog
import social.tbone.ui.media.rememberVideoPlayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape
import dev.youniversal.theme.YouniversalTheme

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
private val AUDIO_EXTENSIONS = setOf(
    "mp3", "mpga", "mpeg3", "mpg3", "m4a", "m4b", "m4p", "aac", "wav", "flac", "ogg", "oga", "opus",
    "weba", "aif", "aiff", "amr", "mka", "mid", "midi", "wma", "caf",
)
private val VIDEO_EXTENSIONS = setOf(
    "mp4", "webm", "mov", "qt", "m4v", "mkv", "m3u8", "ogv", "3gp", "3g2", "avi",
    "divx", "flv", "wmv", "mpeg", "mpg", "m2v", "m2ts", "m2t", "mts", "ts", "vob",
    "asf", "mxf", "rm", "rmvb", "f4v", "f4p", "dv", "264", "h264", "265", "h265", "hevc",
)

private val NOSTR_URI_REGEX = Regex("""nostr:(note1|nevent1|npub1|nprofile1|naddr1)[a-z0-9]*""")

/** Height of inline images in note cards — tall enough to actually see the picture. */
private val INLINE_IMAGE_HEIGHT = 260.dp

/** Height cap for fitted (real-shape) media frames; the box is unchanged at [INLINE_IMAGE_HEIGHT]. */
private val FITTED_MEDIA_MAX_HEIGHT = 480.dp

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
    data class Image(val url: String, val meta: MediaMeta? = null) : MediaItem()
    data class Video(val url: String, val meta: MediaMeta? = null) : MediaItem()
    data class Audio(val url: String) : MediaItem()
}

/**
 * Display hints a note may state about its media (NIP-92 `imeta`):
 * dimensions ("1024x768"), a BlurHash preview code, a ThumbHash, and for
 * videos a poster frame URL. All optional — most clients include some,
 * older notes include none, and rendering must cope either way.
 */
data class MediaMeta(
    val mime: String? = null,
    val dimension: String? = null,
    val blurhash: String? = null,
    val thumbhash: String? = null,
    val image: String? = null,
) {
    /** Width/height ratio from [dimension], or null when unstated/invalid. */
    val aspectRatio: Float? get() = parseMediaAspectRatio(dimension)
}

/** Parses an imeta "WxH" dimension string into a width/height ratio. */
fun parseMediaAspectRatio(dimension: String?): Float? {
    val parts = dimension?.trim()?.split('x', 'X') ?: return null
    if (parts.size != 2) return null
    val w = parts[0].trim().toFloatOrNull() ?: return null
    val h = parts[1].trim().toFloatOrNull() ?: return null
    if (w <= 0f || h <= 0f) return null
    return (w / h).takeIf { it.isFinite() }
}

data class ParsedContent(
    val text: String,
    val mediaItems: List<MediaItem>,
)

private val QUOTE_URI_REGEX = Regex("""nostr:(note1|nevent1|naddr1)[a-z0-9]+""")

/** All decodable note references, including those following an npub mention. */
fun extractInlineQuoteIds(content: String): List<String> =
    QUOTE_URI_REGEX.findAll(content).mapNotNull { Nip19.nostrUriToEventId(it.value) }
        .distinct().take(32).toList()

fun extractInlineQuoteId(content: String): String? = extractInlineQuoteIds(content).firstOrNull()

internal fun inlineQuoteUri(content: String, eventId: String): String? =
    QUOTE_URI_REGEX.findAll(content).firstOrNull { Nip19.nostrUriToEventId(it.value) == eventId }?.value

/**
 * Removes the quote reference URI(s) from a note's body text.
 *
 * When a note quotes another note we render the quoted note as a card below
 * the body — so the raw `nostr:nevent1…` / `nostr:note1…` string must never
 * also appear inside the text (it looks like a broken link). Removes:
 *  any URI whose decoded event id equals [quotedId].
 * Legitimate inline note references that aren't the quote are left alone.
 */
fun stripQuoteRefs(content: String, quotedId: String): String {
    var out = content
    // Remove only the specific quote; other references, even trailing ones,
    // still need to render and remain accessible.
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

    val text = social.tbone.util.HTTP_URL_REGEX.replace(withoutNostrUris) { match ->
        val url = social.tbone.util.trimUrlPunctuation(match.value)
        val suffix = match.value.substring(url.length)
        // Only inspect the final path component, not the host, query or fragment.
        val path = url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
        val file = path.substringAfterLast('/')
        val ext = if ('.' in file) file.substringAfterLast('.').lowercase() else ""
        when {
            ext in IMAGE_EXTENSIONS -> { mediaItems.add(MediaItem.Image(url)); suffix }
            ext in AUDIO_EXTENSIONS -> { mediaItems.add(MediaItem.Audio(url)); suffix }
            ext in VIDEO_EXTENSIONS -> { mediaItems.add(MediaItem.Video(url)); suffix }
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
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    var videoViewerUrl by remember { mutableStateOf<String?>(null) }
    // Image reveal state remains local to this note.
    var revealedImages by remember { mutableStateOf(setOf<String>()) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mediaItems.forEach { item ->
            when (item) {
                is MediaItem.Image -> when (imageMode) {
                    ImageLoadMode.OFF -> {
                        if (item.url in revealedImages) {
                            InlineImage(url = item.url, meta = item.meta, lowQuality = false, onOpen = { imageViewerUrl = item.url })
                        } else {
                            MediaPlaceholder(label = "[ image · tap to show ]", url = item.url, onOpen = { revealedImages = revealedImages + item.url })
                        }
                    }
                    ImageLoadMode.ON ->
                        InlineImage(url = item.url, meta = item.meta, lowQuality = false, onOpen = { imageViewerUrl = item.url })
                    ImageLoadMode.LOW_QUALITY ->
                        InlineImage(url = item.url, meta = item.meta, lowQuality = true, onOpen = { imageViewerUrl = item.url })
                }
                is MediaItem.Audio -> InlineAudioPlayer(url = item.url)
                is MediaItem.Video -> {
                    // A token identifies this particular card, not just the URL: a
                    // quote and a feed item may contain the same video.
                    val token = remember(item.url) { Any() }
                    val selected = ActiveInlineVideo.owner === token
                    DisposableEffect(token) {
                        onDispose { if (ActiveInlineVideo.owner === token) ActiveInlineVideo.owner = null }
                    }
                    if (selected && videoViewerUrl == null) {
                        Box(Modifier.testTag("active-inline-video")) {
                            InlineVideo(
                                url = item.url,
                                meta = item.meta,
                                quality = if (videoQuality == VideoQuality.OFF) VideoQuality.REGULAR else videoQuality,
                                playWhenReady = true,
                                onOpen = {
                                    ActiveInlineVideo.owner = null
                                    videoViewerUrl = item.url
                                },
                                onBackground = { if (ActiveInlineVideo.owner === token) ActiveInlineVideo.owner = null },
                            )
                        }
                    } else {
                        VideoPlaceholder(
                            url = item.url,
                            meta = item.meta,
                            onOpen = { ActiveInlineVideo.owner = token },
                            onFullscreen = {
                                ActiveInlineVideo.owner = null
                                videoViewerUrl = item.url
                            },
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

/** Only a deliberately selected inline video can own a player. */
private object ActiveInlineVideo {
    var owner: Any? by mutableStateOf(null)
}

/** Tap the video to play/pause; only the fullscreen button opens the viewer. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineVideo(
    url: String,
    meta: MediaMeta? = null,
    quality: VideoQuality,
    playWhenReady: Boolean,
    onOpen: () -> Unit,
    onBackground: () -> Unit,
) {
    val context = LocalContext.current
    val playerState = rememberVideoPlayer(url, quality, playWhenReady)
    val player = playerState.player
    if (player == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(INLINE_IMAGE_HEIGHT)
                .clip(appCornerShape(MaterialTheme.shapes.medium))
                .background(BonyColors.SurfaceAlt)
                .border(1.dp, BonyColors.Rule),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = playerState.errorMessage ?: "loading video…",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            FullscreenButton(onOpen, Modifier.align(Alignment.BottomEnd))
        }
        return
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onBackground() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val playbackFailed = remember(player) { mutableStateOf(false) }
    val isPlaying = remember(player) { mutableStateOf(player.isPlaying) }
    val videoAspectRatio = remember(player) { mutableStateOf(meta?.aspectRatio) }

    // PlayerView reports the actual display dimensions after track selection.
    // Using the ratio lets Youniversal remove only the unused letterbox area;
    // the visible video is still fitted into the same 260dp × screen-width box.
    DisposableEffect(player) {
        fun updateRatio(size: VideoSize) {
            val ratio = videoDisplayAspectRatio(
                width = size.width,
                height = size.height,
                pixelWidthHeightRatio = size.pixelWidthHeightRatio,
                rotationDegrees = size.unappliedRotationDegrees,
            ) ?: return
            videoAspectRatio.value = ratio
        }

        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) = updateRatio(videoSize)
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying.value = playing }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackFailed.value = true
            }
        }
        player.addListener(listener)
        isPlaying.value = player.isPlaying
        updateRatio(player.videoSize)
        onDispose { player.removeListener(listener) }
    }

    if (playbackFailed.value) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(INLINE_IMAGE_HEIGHT)
                .clip(appCornerShape(MaterialTheme.shapes.medium))
                .background(BonyColors.SurfaceAlt)
                .border(1.dp, BonyColors.Rule),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "video preview unavailable",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            FullscreenButton(onOpen, Modifier.align(Alignment.BottomEnd))
        }
        return
    }

    val fittedEnabled = mediaFittedEnabled()
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val fittedSize = if (fittedEnabled) {
            videoAspectRatio.value?.let { fitMediaFrame(maxWidth, FITTED_MEDIA_MAX_HEIGHT, it) }
        } else {
            null
        }
        val shape = appCornerShape(MaterialTheme.shapes.medium)
        val frameModifier = if (fittedSize != null) {
            Modifier.width(fittedSize.width).height(fittedSize.height)
        } else {
            Modifier.fillMaxWidth().height(INLINE_IMAGE_HEIGHT)
        }
        Box(
            modifier = frameModifier
                .clip(shape)
                .background(Color.Black, shape)
                .border(1.dp, BonyColors.Rule, shape),
        ) {
            AndroidView(
                factory = { context ->
                    (LayoutInflater.from(context)
                        .inflate(R.layout.inline_video_player_view, null, false) as PlayerView).apply {
                        this.player = player
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        keepScreenOn = player.isPlaying
                    }
                },
                update = { it.player = player; it.keepScreenOn = isPlaying.value },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().combinedClickable(
                onClick = { if (player.playWhenReady) player.pause() else player.play() },
                onLongClick = { copyNoteLink(context, url) },
            ), contentAlignment = Alignment.Center) {
                if (!isPlaying.value) VideoPlayBadge()
            }
            FullscreenButton({ player.pause(); onOpen() }, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun FullscreenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.padding(6.dp).size(48.dp)
            .background(Color.Black.copy(alpha = 0.68f), CircleShape)
            .semantics { contentDescription = "Open fullscreen video" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Fullscreen, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun VideoPlayBadge() {
    Box(
        modifier = Modifier.size(54.dp)
            .background(Color.Black.copy(alpha = 0.62f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(23.dp)) {
            val play = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.10f)
                lineTo(size.width * 0.84f, size.height * 0.50f)
                lineTo(size.width * 0.24f, size.height * 0.90f)
                close()
            }
            drawPath(play, Color.White)
        }
    }
}

/**
 * Inline image rendered by Coil. Decodes at a bounded resolution so huge
 * uploads don't blow up memory; tapping opens the in-app viewer at the
 * original resolution (zoom, close, download — no browser).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineImage(url: String, meta: MediaMeta? = null, lowQuality: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    val fittedEnabled = mediaFittedEnabled()
    // The note's stated shape sizes the frame before anything downloads;
    // the loaded image's real shape refines it afterwards.
    var aspectRatio by remember(url, lowQuality) { mutableStateOf(meta?.aspectRatio) }
    // Keep the complete URL: query parameters can identify different media.
    val cacheKey = remember(url) { "inline-image-animated:$url" }
    val request = remember(url, lowQuality) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .crossfade(false)
            .apply {
                if (url.substringBefore('?').substringBefore('#').endsWith(".gif", ignoreCase = true)) {
                    decoderFactory(GifDecoder.Factory())
                }
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

    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // Fitted layout: size the rounded surface to the image itself,
        // removing empty letterbox area without cropping the content.
        val fittedSize = if (fittedEnabled) {
            aspectRatio?.let { fitMediaFrame(maxWidth, FITTED_MEDIA_MAX_HEIGHT, it) }
        } else {
            null
        }
        val blurPainter = if (fittedEnabled) rememberBlurPainter(meta) else null
        val shape = appCornerShape(MaterialTheme.shapes.medium)
        val frameModifier = if (fittedSize != null) {
            Modifier.width(fittedSize.width).height(fittedSize.height)
        } else {
            Modifier.fillMaxWidth().height(INLINE_IMAGE_HEIGHT)
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = "image",
            contentScale = ContentScale.Fit,
            modifier = frameModifier
                .clip(shape)
                .background(BonyColors.SurfaceAlt, shape)
                .border(1.dp, BonyColors.Rule, shape)
                .combinedClickable(onClick = onOpen, onLongClick = { copyNoteLink(context, url) }),
            onSuccess = { result ->
                val size = result.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    val ratio = size.width / size.height
                    if (ratio.isFinite() && ratio > 0f) aspectRatio = ratio
                }
            },
            loading = {
                if (blurPainter != null) {
                    Image(
                        painter = blurPainter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    MediaStripes(Modifier.fillMaxSize())
                }
            },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoPlaceholder(url: String, meta: MediaMeta? = null, onOpen: () -> Unit, onFullscreen: () -> Unit) {
    val context = LocalContext.current
    val shape = appCornerShape(MaterialTheme.shapes.medium)
    val thumbnailsEnabled = LocalVideoThumbnails.current
    val fittedEnabled = mediaFittedEnabled()
    val posterUrl = meta?.image?.takeIf { thumbnailsEnabled }
    val blurPainter = if (posterUrl != null || fittedEnabled) rememberBlurPainter(meta) else null

    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val fittedSize = if (fittedEnabled) {
            meta?.aspectRatio?.let { fitMediaFrame(maxWidth, FITTED_MEDIA_MAX_HEIGHT, it) }
        } else {
            null
        }
        val frameModifier = if (fittedSize != null) {
            Modifier.width(fittedSize.width).height(fittedSize.height)
        } else {
            Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT)
        }
        Box(
            modifier = Modifier.testTag("video-placeholder").then(frameModifier).clip(shape)
                .background(BonyColors.SurfaceAlt, shape).border(1.dp, BonyColors.Rule, shape)
                .combinedClickable(onClick = onOpen, onLongClick = { copyNoteLink(context, url) }),
            contentAlignment = Alignment.Center,
        ) {
            MediaStripes(Modifier.matchParentSize())
            if (posterUrl != null) {
                // The still the poster supplied with the note (NIP-92 imeta
                // "image"). Nothing is extracted on-device; when no still
                // was supplied, the blurred preview / stripes remain.
                AsyncImage(
                    model = posterUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholder = blurPainter,
                    error = blurPainter,
                    modifier = Modifier.matchParentSize(),
                )
            } else if (blurPainter != null) {
                Image(
                    painter = blurPainter,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            VideoPlayBadge()
            FullscreenButton(onFullscreen, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/** Diagonal stripe placeholder matching the design system media block spec. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaPlaceholder(label: String, url: String, onOpen: () -> Unit) {
    val context = LocalContext.current
    val shape = appCornerShape(MaterialTheme.shapes.medium)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(PLACEHOLDER_HEIGHT)
            .clip(shape)
            .background(BonyColors.SurfaceAlt, shape)
            .border(1.dp, BonyColors.Rule, shape)
            .combinedClickable(onClick = onOpen, onLongClick = { copyNoteLink(context, url) }),
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

/** Small decoded BlurHash previews, shared across rows (key = the hash itself). */
private val blurPainterCache = android.util.LruCache<String, BitmapPainter>(128)

/**
 * The blurred stand-in for [meta]'s media, decoded from the note's BlurHash
 * at thumbnail size off the main thread. Null when the note carries no
 * usable BlurHash (stripes remain the fallback).
 */
@Composable
internal fun rememberBlurPainter(meta: MediaMeta?): BitmapPainter? {
    val blurhash = meta?.blurhash?.takeIf { BlurHash.isValid(it) }
    val painter by produceState<BitmapPainter?>(null, blurhash) {
        if (blurhash == null) return@produceState
        blurPainterCache.get(blurhash)?.let { value = it; return@produceState }
        value = withContext(Dispatchers.Default) {
            val ratio = meta?.aspectRatio
            val width = 64
            val height = if (ratio != null && ratio > 0f) {
                (width / ratio).toInt().coerceIn(16, 96)
            } else 64
            BlurHash.decodeToBitmap(blurhash, width, height)
                ?.let { BitmapPainter(it.asImageBitmap()) }
        }
        value?.let { blurPainterCache.put(blurhash, it) }
    }
    return painter
}

/** True when media frames should take the media's real shape (setting or Youniversal). */
@Composable
internal fun mediaFittedEnabled(): Boolean =
    LocalMediaFitted.current || YouniversalTheme.enabled

/** The content dimensions of a media item fitted into the existing preview bounds. */
internal data class MediaFrameSize(val width: androidx.compose.ui.unit.Dp, val height: androidx.compose.ui.unit.Dp)

/** Returns the displayed aspect ratio after pixel aspect and video rotation are applied. */
internal fun videoDisplayAspectRatio(
    width: Int,
    height: Int,
    pixelWidthHeightRatio: Float,
    rotationDegrees: Int,
): Float? {
    if (width <= 0 || height <= 0 || !pixelWidthHeightRatio.isFinite() || pixelWidthHeightRatio <= 0f) {
        return null
    }
    val encodedRatio = width * pixelWidthHeightRatio / height
    if (!encodedRatio.isFinite() || encodedRatio <= 0f) return null
    val displayedRatio = if (rotationDegrees % 180 != 0) 1f / encodedRatio else encodedRatio
    return displayedRatio.takeIf { it.isFinite() && it > 0f }
}

/**
 * Returns the actual size occupied by media with [aspectRatio] when fitted
 * inside the established full-width × max-height preview. This preserves the
 * old rendered content size while allowing Youniversal to remove empty space
 * around portrait/landscape media.
 */
internal fun fitMediaFrame(
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    aspectRatio: Float,
): MediaFrameSize? {
    val width = maxWidth.value
    val height = maxHeight.value
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
        !aspectRatio.isFinite() || aspectRatio <= 0f
    ) return null

    val fittedWidth = minOf(width, height * aspectRatio)
    val fittedHeight = minOf(height, width / aspectRatio)
    return MediaFrameSize(fittedWidth.dp, fittedHeight.dp)
}
