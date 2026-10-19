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
    private const val MAX_OUTPUT_DIMENSION = 3840
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
            val scale = minOf(1f, MAX_OUTPUT_DIMENSION.toFloat() / maxOf(card.width, card.height))
            val outputWidth = evenDimension(card.width * scale)
            val outputHeight = evenDimension(card.height * scale)
            val scaledSlot = android.graphics.RectF(
                slot.left * outputWidth / card.width,
                slot.top * outputHeight / card.height,
                slot.right * outputWidth / card.width,
                slot.bottom * outputHeight / card.height,
            )
            val slotWidth = evenDimension(scaledSlot.width())
            val slotHeight = evenDimension(scaledSlot.height())
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

    private fun evenDimension(value: Float): Int {
        val whole = value.toInt().coerceAtLeast(2)
        return if (whole % 2 == 0) whole else (whole - 1).coerceAtLeast(2)
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
            return OverlaySettings.Builder()
                .setScale(slot.width() / outputWidth, slot.height() / outputHeight)
                .setOverlayFrameAnchor(0f, 0f)
                .setBackgroundFrameAnchor(centerX, centerY)
                .build()
        }

        private companion object {
            const val VIDEO_SEQUENCE_INDEX = 0
        }
    }
}
