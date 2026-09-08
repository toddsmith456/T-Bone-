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
 * Prepares media before upload:
 *  - IMAGES: re-encoded (which strips all EXIF/embedded metadata), orientation
 *    applied, optionally compressed — with a quality loop that keeps the file
 *    under an optional byte cap (used for the 800 KB avatar/banner limit).
 *  - VIDEOS: optionally transcoded with Media3 Transformer (re-encode also
 *    drops embedded metadata); otherwise copied as-is (honest: a raw copy
 *    keeps whatever metadata the source file had).
 */
object MediaProcessor {

    /** The profile-picture / banner cap: 800 KB. */
    const val AVATAR_MAX_BYTES = 800L * 1024L

    private const val MAX_IMAGE_DIMENSION = 2048

    /** Re-encodes an image URI to a clean file. */
    suspend fun prepareImage(
        context: Context,
        uri: Uri,
        maxBytes: Long? = null,
        compress: Boolean = true,
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
            var targetBytes: Long? = maxBytes

            // Compress loop: lower quality until under the cap (or min quality).
            while (true) {
                FileOutputStream(out).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos)
                }
                val size = out.length()
                val cap = targetBytes
                if (cap == null || size <= cap || quality <= 55) break
                quality -= 8
            }
            if (out.length() > (maxBytes ?: Long.MAX_VALUE)) {
                // Still too big at min quality — downscale and retry.
                var b = bmp
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
                    b = scaled
                    if (out.length() <= (maxBytes ?: Long.MAX_VALUE)) break
                    maxDim -= 300
                }
            }
            bmp.recycle()
            out
        }.getOrNull()
    }

    /**
     * Prepares a video for upload. When [compress] is true the video is
     * transcoded with Media3 Transformer (re-encode → embedded metadata gone,
     * resolution capped to 1280px, reasonable bitrate). Otherwise the bytes are
     * copied untouched.
     */
    suspend fun prepareVideo(context: Context, uri: Uri, compress: Boolean): File? {
        if (!compress) {
            return withContext(Dispatchers.IO) {
                runCatching {
                    val out = File(context.cacheDir, "tbone_video_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    out
                }.getOrNull()
            }
        }
        return transcodeVideo(context, uri)
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
