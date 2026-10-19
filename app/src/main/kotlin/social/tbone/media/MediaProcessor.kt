package social.tbone.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Codec
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
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
 *    drops embedded metadata); unsupported sources fall back to a raw copy
 *    with their original MIME type and extension.
 *  - AUDIO: copied unchanged by default; opt-in AAC/M4A compression is kept only
 *    when smaller, otherwise the original bytes, MIME type and extension win.
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
    /** Blossom video uploads are capped at 10 MB (decimal) before and after processing. */
    const val VIDEO_MAX_BYTES = 10_000_000L
    private const val VIDEO_TOO_BIG_MESSAGE = "video is too big"
    private const val AUDIO_COMPRESSED_MIME = "audio/mp4"
    private const val AUDIO_COMPRESSED_EXT = "m4a"

    /** Re-encodes an image URI to a clean JPEG and reports it as such. */
    suspend fun prepareImageMedia(
        context: Context,
        uri: Uri,
        maxBytes: Long? = null,
        compress: Boolean = true,
    ): PreparedMedia? = prepareImageFile(context, uri, maxBytes, compress)
        ?.let { PreparedMedia(it, IMAGE_MIME, IMAGE_EXT) }

    /**
     * Prepares a video for upload. Media3 Transformer is tried first when
     * compression is enabled. If the source codec/container is not supported,
     * upload the original bytes with their detected MIME type and extension
     * rather than rejecting a file the user's Blossom server may accept.
     */
    suspend fun prepareVideoMedia(
        context: Context,
        uri: Uri,
        compress: Boolean = true,
    ): PreparedMedia? {
        val (sourceMime, sourceExtension, sourceSize) = withContext(Dispatchers.IO) {
            val (mime, extension) = videoSourceType(context, uri)
            Triple(mime, extension, sourceSizeOf(context, uri))
        }
        if (sourceSize > VIDEO_MAX_BYTES) throw IllegalArgumentException(VIDEO_TOO_BIG_MESSAGE)

        // Some content providers omit SIZE or return zero. Copy those sources
        // once with a hard byte cap before invoking Media3, both to measure them
        // accurately and to avoid feeding an unexpectedly huge stream to a codec.
        val sourceCopy = if (sourceSize <= 0L) {
            copyVideoToCache(context, uri, sourceExtension) ?: return null
        } else null
        val sourceUri = sourceCopy?.let { Uri.fromFile(it) } ?: uri

        if (!compress) {
            val original = sourceCopy ?: copyVideoToCache(context, uri, sourceExtension) ?: return null
            return PreparedMedia(original, sourceMime, sourceExtension)
        }

        val transcoded = try {
            transcodeVideo(context, sourceUri)
        } catch (cancelled: CancellationException) {
            sourceCopy?.delete()
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "video transcode unavailable; uploading original file")
            null
        }
        if (transcoded != null && transcoded.exists() && transcoded.length() > 0L) {
            if (transcoded.length() <= VIDEO_MAX_BYTES) {
                sourceCopy?.delete()
                return PreparedMedia(transcoded, VIDEO_MIME, VIDEO_EXT)
            }
            transcoded.delete()
        }
        transcoded?.delete()

        // A transcode can be larger than its input. Fall back only when the
        // original bytes are within the cap; otherwise surface the exact message.
        val original = sourceCopy ?: copyVideoToCache(context, uri, sourceExtension) ?: return null
        if (original.length() > VIDEO_MAX_BYTES) {
            original.delete()
            throw IllegalArgumentException(VIDEO_TOO_BIG_MESSAGE)
        }
        return PreparedMedia(original, sourceMime, sourceExtension)
    }

    /**
     * Prepares audio for upload. Compression is opt-in: when disabled, source
     * bytes (including MP3) are copied unchanged. When enabled, Media3 creates
     * a compact AAC/M4A rendition; unsupported inputs fall back to the original.
     */
    suspend fun prepareAudioMedia(
        context: Context,
        uri: Uri,
        compress: Boolean = false,
    ): PreparedMedia? {
        val (declaredMime, nameExtension, sourceSize) = withContext(Dispatchers.IO) {
            Triple(
                sourceMimeOf(context, uri)?.takeIf { MimeTypes.isAudio(it) },
                MimeTypes.extensionFromFileName(sourceNameOf(context, uri)),
                sourceSizeOf(context, uri),
            )
        }
        val inferredMime = MimeTypes.mimeForExtension(nameExtension)?.takeIf { MimeTypes.isAudio(it) }
        val sourceMime = inferredMime ?: declaredMime ?: return null
        val sourceExtension = nameExtension.takeIf { inferredMime != null }
            ?: MimeTypes.extensionFor(sourceMime).ifBlank { return null }

        // A few document providers omit OpenableColumns.SIZE. Copy those sources
        // once so the comparison uses their real byte count and a failed or
        // unhelpful transcode can still fall back to the exact original bytes.
        val comparisonCopy = if (compress && sourceSize <= 0L) {
            copyToCache(context, uri, sourceExtension, "tbone_audio")
        } else null
        val comparisonSize = comparisonCopy?.length()?.takeIf { it > 0L } ?: sourceSize
        val transcodeUri = comparisonCopy?.let { Uri.fromFile(it) } ?: uri

        if (compress) {
            val transcoded = try {
                transcodeAudio(context, transcodeUri)
            } catch (cancelled: CancellationException) {
                comparisonCopy?.delete()
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "audio transcode unavailable; uploading original file")
                null
            }
            if (transcoded != null && transcoded.exists() && transcoded.length() > 0L) {
                // Never make a file larger just because compression was requested.
                if (comparisonSize <= 0L || transcoded.length() < comparisonSize) {
                    comparisonCopy?.delete()
                    return PreparedMedia(transcoded, AUDIO_COMPRESSED_MIME, AUDIO_COMPRESSED_EXT)
                }
                transcoded.delete()
            }
            comparisonCopy?.let { return PreparedMedia(it, sourceMime, sourceExtension) }
        }

        val original = if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
        } else {
            copyToCache(context, uri, sourceExtension, "tbone_audio")
        } ?: return null
        return PreparedMedia(original, sourceMime, sourceExtension)
    }

    /** The MIME type the content resolver reports for [uri] (may be null). */
    private fun sourceMimeOf(context: Context, uri: Uri): String? =
        runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("application/octet-stream", ignoreCase = true) }

    private fun sourceNameOf(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/')
        val name = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sourceSizeOf(context: Context, uri: Uri): Long = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf { it.exists() }?.length() ?: -1L
        } else {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            } ?: -1L
        }
    }.getOrDefault(-1L)

    private fun videoSourceType(context: Context, uri: Uri): Pair<String, String> {
        val declaredMime = sourceMimeOf(context, uri)
        val nameExtension = MimeTypes.extensionFromFileName(sourceNameOf(context, uri))
        val inferredMime = MimeTypes.mimeForExtension(nameExtension)?.takeIf { MimeTypes.isVideo(it) }
        // Prefer a known file extension over a generic provider MIME; several
        // document providers mislabel Matroska/AVI files as video/mp4.
        val mime = inferredMime
            ?: declaredMime?.takeIf { MimeTypes.isVideo(it) }
            ?: declaredMime
            ?: VIDEO_MIME
        val extension = nameExtension.ifBlank {
            MimeTypes.extensionFor(mime).ifBlank { VIDEO_EXT }
        }
        return mime to extension
    }

    private suspend fun copyToCache(
        context: Context,
        uri: Uri,
        extension: String,
        prefix: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val suffix = extension.ifBlank { "bin" }
            val out = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.$suffix")
            val input = if (uri.scheme == "file") {
                uri.path?.let(::File)?.takeIf { it.exists() }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            } ?: return@runCatching null
            input.use { source -> out.outputStream().use { output -> source.copyTo(output) } }
            out.takeIf { it.exists() && it.length() > 0L }
        }.getOrNull()
    }

    /** Streams an original video into cache and aborts as soon as it exceeds 10 MiB. */
    private suspend fun copyVideoToCache(
        context: Context,
        uri: Uri,
        extension: String,
    ): File? = withContext(Dispatchers.IO) {
        val suffix = extension.ifBlank { VIDEO_EXT }
        val out = File(context.cacheDir, "tbone_video_${System.currentTimeMillis()}.$suffix")
        val input = if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf { it.exists() }?.inputStream()
        } else {
            context.contentResolver.openInputStream(uri)
        } ?: return@withContext null

        try {
            input.use { source ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > VIDEO_MAX_BYTES) {
                            throw IllegalArgumentException(VIDEO_TOO_BIG_MESSAGE)
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            out.takeIf { it.exists() && it.length() > 0L }
        } catch (error: Throwable) {
            out.delete()
            throw error
        }
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

    @OptIn(androidx.media3.common.util.UnstableApi::class)
    private suspend fun transcodeAudio(context: Context, uri: Uri): File? {
        val out = File(context.cacheDir, "tbone_audio_comp_${System.currentTimeMillis()}.m4a")
        val edited = EditedMediaItem.Builder(MediaItem.fromUri(uri))
            .setRemoveVideo(true)
            .setRemoveAudio(false)
            .build()
        val composition = Composition.Builder(EditedMediaItemSequence(edited)).build()
        val defaultEncoderFactory = DefaultEncoderFactory.Builder(context).build()
        // Media3 1.4 exposes bitrate on the requested audio Format, while the
        // newer AudioEncoderSettings convenience API is not in this version.
        // Wrap its factory so AAC is encoded at a modest voice-note bitrate.
        val encoderFactory = object : Codec.EncoderFactory {
            override fun createForAudioEncoding(inputFormat: Format): Codec =
                defaultEncoderFactory.createForAudioEncoding(
                    inputFormat.buildUpon().setAverageBitrate(64_000).build(),
                )

            override fun createForVideoEncoding(inputFormat: Format): Codec =
                defaultEncoderFactory.createForVideoEncoding(inputFormat)

            override fun audioNeedsEncoding(): Boolean = true

            override fun videoNeedsEncoding(): Boolean = defaultEncoderFactory.videoNeedsEncoding()
        }

        return suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out) else out.delete()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        Timber.w(exportException, "audio transcode failed")
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            runCatching {
                transformer.start(composition, out.absolutePath)
            }.onFailure {
                out.delete()
                if (cont.isActive) cont.resumeWithException(it)
            }
            cont.invokeOnCancellation {
                transformer.cancel()
                out.delete()
            }
        }
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
