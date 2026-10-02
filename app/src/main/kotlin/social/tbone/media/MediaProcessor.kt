package social.tbone.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A file that has been prepared for upload, together with the MIME type and
 * extension that **describe the bytes on disk**.
 *
 * The MIME type is not cosmetic: Blossom servers validate it against the body
 * (`Content-Type header does not match the file content`) and store it as the
 * blob's `Content-Type`, which is what every client uses to render the media
 * afterwards. Reporting the *original* picker MIME while sending re-encoded
 * JPEG bytes — as the previous implementation did — is what made some servers
 * refuse uploads outright.
 */
data class PreparedMedia(
    val file: File,
    val mime: String,
    val extension: String,
) {
    val sizeBytes: Long get() = file.length()
}

/**
 * Prepares media before upload:
 *  - IMAGES: re-encoded as JPEG (which strips all EXIF/embedded metadata),
 *    orientation applied, optionally compressed — with a quality loop that
 *    keeps the file under an optional byte cap (used for the 800 KB
 *    avatar/banner limit).
 *  - VIDEOS: optionally transcoded with Media3 Transformer (re-encode also
 *    drops embedded metadata); otherwise copied as-is (honest: a raw copy
 *    keeps whatever metadata the source file had).
 *
 * Every entry point returns a [PreparedMedia] so callers always know the true
 * MIME type and extension of the prepared file.
 */
object MediaProcessor {

    /** The profile-picture / banner cap: 800 KB. */
    const val AVATAR_MAX_BYTES = 800L * 1024L

    private const val MAX_IMAGE_DIMENSION = 2048

    /** MIME + extension of the re-encoded image pipeline (always JPEG). */
    private const val IMAGE_MIME = "image/jpeg"
    private const val IMAGE_EXT = "jpg"

    /** MIME + extension of the Media3 transcode output (always MP4). */
    private const val VIDEO_MIME = "video/mp4"
    private const val VIDEO_EXT = "mp4"

    /** Re-encodes an image URI to a clean JPEG and reports it as such. */
    suspend fun prepareImageMedia(
        context: Context,
        uri: Uri,
        maxBytes: Long? = null,
        compress: Boolean = true,
    ): PreparedMedia? = prepareImageFile(context, uri, maxBytes, compress)
        ?.let { PreparedMedia(it, IMAGE_MIME, IMAGE_EXT) }

    /**
     * Prepares a video for upload. When [compress] is true the video is
     * transcoded to MP4 with Media3 Transformer; otherwise the bytes are copied
     * untouched and keep the source's own MIME type (and its metadata).
     */
    suspend fun prepareVideoMedia(
        context: Context,
        uri: Uri,
        compress: Boolean = true,
    ): PreparedMedia? {
        if (!compress) {
            val sourceMime = sourceMimeOf(context, uri) ?: VIDEO_MIME
            val sourceExt = MimeTypes.extensionFor(sourceMime)
            return copyToCache(context, uri, sourceExt)?.let {
                PreparedMedia(it, sourceMime, sourceExt)
            }
        }
        return transcodeVideo(context, uri)?.let {
            PreparedMedia(it, VIDEO_MIME, VIDEO_EXT)
        }
    }

    /** The MIME type the content resolver reports for [uri] (may be null). */
    private fun sourceMimeOf(context: Context, uri: Uri): String? =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()

    private suspend fun copyToCache(context: Context, uri: Uri, extension: String): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val suffix = if (extension.isBlank()) "bin" else extension
                val out = File(context.cacheDir, "tbone_video_${System.currentTimeMillis()}.$suffix")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.takeIf { it.exists() && it.length() > 0 }
            }.getOrNull()
        }

    private suspend fun prepareImageFile(
        context: Context,
        uri: Uri,
        maxBytes: Long?,
        compress: Boolean,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val src = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val orientation = readExifOrientation(context, uri)

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(src, 0, src.size, bounds)

            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_DIMENSION ||
                bounds.outHeight / sample > MAX_IMAGE_DIMENSION
            ) sample *= 2

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeByteArray(src, 0, src.size, opts) ?: return@runCatching null
            bmp = applyOrientation(bmp, orientation)

            val out = File(context.cacheDir, "tbone_media_${System.currentTimeMillis()}.jpg")
            var quality = if (compress) 88 else 100
            val targetBytes: Long? = maxBytes

            // Compress loop: lower quality until under the cap (or min quality).
            while (true) {
                FileOutputStream(out).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                }
                val size = out.length()
                if (targetBytes == null || size <= targetBytes || quality <= 55) break
                quality -= 8
            }
            if (out.length() > (maxBytes ?: Long.MAX_VALUE)) {
                // Still too big at min quality — downscale and retry.
                var maxDim = 1600
                while (maxDim >= 400) {
                    val scale = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
                    val scaled = Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt().coerceAtLeast(1),
                        (bmp.height * scale).toInt().coerceAtLeast(1), true,
                    )
                    FileOutputStream(out).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 70, fos)
                    }
                    if (out.length() <= (maxBytes ?: Long.MAX_VALUE)) break
                    maxDim -= 300
                }
            }
            bmp.recycle()
            out
        }.getOrNull()
    }

    private suspend fun transcodeVideo(context: Context, uri: Uri): File? {
        val out = File(context.cacheDir, "tbone_video_comp_${System.currentTimeMillis()}.mp4")
        val presentation = Presentation.createForHeight(1280)
        val effects = Effects(emptyList(), listOf(presentation))
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setEffects(effects)
            .setRemoveAudio(false)
            .build()
        val sequence = EditedMediaItemSequence(edited)
        val composition = Composition.Builder(sequence).build()

        return suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    cont.resume(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    Timber.w(exportException, "video transcode failed")
                    out.delete()
                    cont.resumeWithException(exportException)
                }
            })
                .build()
            runCatching {
                transformer.start(composition, out.absolutePath)
            }.onFailure {
                cont.resumeWithException(it)
            }
            cont.invokeOnCancellation { transformer.cancel() }
        }
    }

    private fun readExifOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
