package social.tbone.ui.feed

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.VideoCompositorSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.common.MediaItem
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import social.tbone.di.ImageClientProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Builds a playable MP4 with a still share-card frame and one live video window. */
object ShareCardVideoGenerator {
    private const val MAX_VIDEO_BYTES = 300L * 1024L * 1024L
    private const val FRAME_RATE = 30

    @androidx.media3.common.util.UnstableApi
    internal suspend fun render(
        context: Context,
        card: RenderedShareCard,
        video: ShareCardMedia,
    ): File {
        val slot = card.videoSlot ?: error("The share card has no video window")
        val extension = (video.mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?.takeIf { it.isNotBlank() }
            ?: video.url.substringBefore('?').substringBefore('#').substringAfterLast('.', "mp4"))
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "mp4"
        val input = File(context.cacheDir, "share-video-${UUID.randomUUID()}.$extension")
        val output = File(context.cacheDir, "share_cards/note-${UUID.randomUUID()}.mp4")
        output.parentFile?.mkdirs()
        var completed = false
        try {
            downloadVideo(video.url, input)
            val durationMs = readDurationMs(input)
            require(durationMs > 0L) { "Could not read the source video duration." }
            val (outputWidth, outputHeight) = ShareCardVideoLayout.outputSize(card.width, card.height)
            val frame = ShareCardVideoLayout.slotInOutput(
                left = slot.left,
                top = slot.top,
                right = slot.right,
                bottom = slot.bottom,
                cardWidth = card.width,
                cardHeight = card.height,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
            )
            val scaledSlot = android.graphics.RectF(frame.left, frame.top, frame.right, frame.bottom)
            val slotWidth = frame.width
            val slotHeight = frame.height
            val backgroundItem = MediaItem.Builder()
                .setUri(Uri.fromFile(card.file))
                .setMimeType("image/png")
                .setImageDurationMs(durationMs)
                .build()
            val background = EditedMediaItem.Builder(backgroundItem)
                .setFrameRate(FRAME_RATE)
                .setRemoveAudio(true)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(
                    outputWidth,
                    outputHeight,
                    Presentation.LAYOUT_SCALE_TO_FIT,
                ))))
                .build()

            val videoItemBuilder = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
            video.mimeType?.takeIf { it.startsWith("video/") }?.let(videoItemBuilder::setMimeType)
            val videoEdited = EditedMediaItem.Builder(videoItemBuilder.build())
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(Presentation.createForWidthAndHeight(slotWidth, slotHeight, Presentation.LAYOUT_SCALE_TO_FIT)),
                    ),
                )
                .setRemoveAudio(false)
                .build()
            // Media3 places earlier registered inputs above later ones, so put
            // the live video first and the still card underneath it.
            val composition = Composition.Builder(
                EditedMediaItemSequence(videoEdited),
                EditedMediaItemSequence(background),
            )
                .setVideoCompositorSettings(CardVideoCompositorSettings(outputWidth, outputHeight, scaledSlot))
                .build()

            export(context, composition, output)
            require(output.isFile && output.length() > 0L) { "The video card could not be created." }
            // A container that exists is not a playable card: some exports end
            // up with a zero-duration or video-less file. Treat that as a
            // failure so the caller shares the still card instead of a dud.
            require(readDurationMs(output) > 0L && readVideoTrackPresent(output)) {
                "The video card could not be created."
            }
            completed = true
            return output
        } finally {
            input.delete()
            if (completed) {
                // The static card is no longer needed after a successful MP4.
                card.file.delete()
            } else {
                // Keep the still image for the caller's safe-share fallback.
                output.delete()
            }
        }
    }

    private suspend fun export(context: Context, composition: Composition, output: File) =
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit) else output.delete()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        output.delete()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            runCatching { transformer.start(composition, output.absolutePath) }
                .onFailure {
                    output.delete()
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            continuation.invokeOnCancellation {
                transformer.cancel()
                output.delete()
            }
        }

    private fun downloadVideo(url: String, destination: File) {
        val request = Request.Builder().url(url).header("Accept", "video/*,*/*;q=0.8").build()
        ImageClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Could not download the video for the share card (${response.code}).")
            val body = response.body ?: throw IOException("The video response was empty.")
            if (body.contentLength() > MAX_VIDEO_BYTES) throw IOException("The video is too large to turn into a share card.")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_VIDEO_BYTES) throw IOException("The video is too large to turn into a share card.")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    /** True when the exported file actually contains a decodable video track. */
    private fun readVideoTrackPresent(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    private class CardVideoCompositorSettings(
        private val outputWidth: Int,
        private val outputHeight: Int,
        private val slot: android.graphics.RectF,
    ) : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: List<Size>): Size = Size(outputWidth, outputHeight)

        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            if (inputId != VIDEO_SEQUENCE_INDEX) return OverlaySettings.Builder().build()
            val centerX = (slot.centerX() / outputWidth) * 2f - 1f
            // NDC's y-axis points upward while Android canvas coordinates point down.
            val centerY = 1f - (slot.centerY() / outputHeight) * 2f
            // Scale MUST stay 1. Media3's compositor already sizes the overlay
            // quad by overlaySize / backgroundSize (OverlayMatrixProvider), and
            // the video input is pre-sized to the slot by Presentation, so the
            // quad is the slot. Scaling it again by slot/output shrank the live
            // window to a stamp and squashed its aspect — the old bug.
            return OverlaySettings.Builder()
                .setScale(ShareCardVideoLayout.OVERLAY_SCALE, ShareCardVideoLayout.OVERLAY_SCALE)
                .setOverlayFrameAnchor(0f, 0f)
                .setBackgroundFrameAnchor(centerX, centerY)
                .build()
        }

        private companion object {
            const val VIDEO_SEQUENCE_INDEX = 0
        }
    }
}

/**
 * Pure pixel mapping from card coordinates to the exported MP4 frame.
 *
 * Kept free of Android types so the geometry can be unit-tested on the JVM:
 * the card is up to 1080 × 8000 px and the encoder wants an even-sized frame
 * no larger than 3840 px on its longest edge, with the video window scaled by
 * exactly the same factor.
 */
internal object ShareCardVideoLayout {
    /**
     * The compositor's overlay scale. Always 1: the video input is already
     * sized to the slot and Media3 scales the overlay by overlay/background
     * itself, so any other value double-scales the video window.
     */
    const val OVERLAY_SCALE = 1f

    private const val MAX_OUTPUT_DIMENSION = 3840

    private fun even(value: Int): Int = if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)

    fun outputSize(cardWidth: Int, cardHeight: Int): Pair<Int, Int> {
        val scale = minOf(1f, MAX_OUTPUT_DIMENSION.toFloat() / maxOf(cardWidth, cardHeight).coerceAtLeast(1))
        return even((cardWidth * scale).toInt().coerceAtLeast(2)) to
            even((cardHeight * scale).toInt().coerceAtLeast(2))
    }

    /** The card-pixel video window mapped into the exported frame, clamped and even-sized. */
    fun slotInOutput(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cardWidth: Int,
        cardHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): VideoSlotFrame {
        val scaleX = outputWidth.toFloat() / cardWidth.coerceAtLeast(1)
        val scaleY = outputHeight.toFloat() / cardHeight.coerceAtLeast(1)
        val l = (left * scaleX).coerceIn(0f, outputWidth - 2f)
        val t = (top * scaleY).coerceIn(0f, outputHeight - 2f)
        val r = (right * scaleX).coerceIn(l + 2f, outputWidth.toFloat())
        val b = (bottom * scaleY).coerceIn(t + 2f, outputHeight.toFloat())
        return VideoSlotFrame(l, t, even((r - l).toInt().coerceAtLeast(2)), even((b - t).toInt().coerceAtLeast(2)))
    }
}

/** Video window in exported-frame pixels. */
internal data class VideoSlotFrame(
    val left: Float,
    val top: Float,
    val width: Int,
    val height: Int,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}
