package social.tbone.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Transparently downscales oversized static avatar images (e.g. 2–8 MB raw phone
 * photos) to crisp ≤256×256 WebP thumbnails (~12–20 KB) BEFORE Coil writes them
 * into `avatar_cache`.
 *
 * Benefits:
 *  - 10×–100× more avatars fit inside the dedicated avatar disk budget.
 *  - Cold-start bitmap decodes read ~15 KB instead of multi-megabyte originals.
 *  - Animated GIFs (`image/gif` or `.gif` URLs) pass through untouched so
 *    animated avatar mode continues working seamlessly.
 *  - Non-decodable/SVG/corrupt/oversized (>12 MB) payloads pass through cleanly
 *    without failing the request or risking OOM.
 */
object AvatarThumbnailNormalizer {

    const val MAX_AVATAR_DIM_PX = 256
    private const val WEBP_QUALITY = 82
    private const val MIN_BYTES_TO_NORMALIZE = 32 * 1024L
    private const val MAX_BYTES_TO_BUFFER = 12 * 1024 * 1024L
    private val WEBP_MEDIA_TYPE = "image/webp".toMediaType()

    /**
     * Wraps a dynamic [OkHttpClient] provider in a Coil [Call.Factory] that
     * normalizes static avatar responses while preserving Tor proxy isolation
     * and [social.tbone.di.TorCallGuard].
     */
    fun callFactory(clientProvider: () -> OkHttpClient): Call.Factory = Call.Factory { request ->
        val baseClient = clientProvider()
        val avatarClient = baseClient.newBuilder()
            .addInterceptor(INTERCEPTOR)
            .build()
        avatarClient.newCall(request)
    }

    internal val INTERCEPTOR = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        normalizeResponse(request, response)
    }

    internal fun shouldNormalizeContentType(contentType: String?, url: String): Boolean {
        val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
        if (cleanUrl.endsWith(".gif") || cleanUrl.endsWith(".svg")) return false
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.isEmpty()) return true // Some Nostr CDNs omit Content-Type; BitmapFactory will validate.
        if (mime == "image/gif" || mime == "image/svg+xml") return false
        return mime.startsWith("image/") || mime == "application/octet-stream"
    }

    private fun normalizeResponse(request: Request, response: Response): Response {
        if (!response.isSuccessful) return response
        val body = response.body ?: return response
        val contentType = body.contentType()?.toString()
        if (!shouldNormalizeContentType(contentType, request.url.toString())) return response

        val declaredLength = body.contentLength()
        if (declaredLength in 0 until MIN_BYTES_TO_NORMALIZE) return response
        if (declaredLength > MAX_BYTES_TO_BUFFER) return response

        val rawBytes = try {
            readBounded(body.byteStream(), MAX_BYTES_TO_BUFFER.toInt())
                ?: throw IOException("Avatar exceeds ${MAX_BYTES_TO_BUFFER / (1024 * 1024)} MB safety limit")
        } catch (e: IOException) {
            throw e
        } finally {
            runCatching { body.close() }
        }

        // Preserve animated GIFs even if server sent a wrong/missing Content-Type.
        if (isGifHeader(rawBytes)) {
            return rebuildResponse(response, rawBytes, body.contentType())
        }

        val normalized = if (rawBytes.size >= MIN_BYTES_TO_NORMALIZE) {
            runCatching { downscaleToWebp(rawBytes) }.getOrNull()
        } else {
            null
        }

        return if (normalized != null && normalized.size < rawBytes.size) {
            rebuildResponse(response, normalized, WEBP_MEDIA_TYPE)
        } else {
            rebuildResponse(response, rawBytes, body.contentType())
        }
    }

    private fun rebuildResponse(
        original: Response,
        bytes: ByteArray,
        mediaType: okhttp3.MediaType?,
    ): Response = original.newBuilder()
        .removeHeader("Content-Length")
        .header("Content-Length", bytes.size.toString())
        .body(bytes.toResponseBody(mediaType))
        .build()

    internal fun isGifHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 6) return false
        return bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == '8'.code.toByte() &&
            (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
            bytes[5] == 'a'.code.toByte()
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): ByteArray? {
        val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n == -1) break
            total += n
            if (total > maxBytes) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun downscaleToWebp(rawBytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val maxDim = maxOf(rawW, rawH)
        var sampleSize = 1
        while (maxDim / (sampleSize * 2) >= MAX_AVATAR_DIM_PX) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return null

        var scaled: Bitmap? = null
        return try {
            val targetBitmap = if (decoded.width > MAX_AVATAR_DIM_PX || decoded.height > MAX_AVATAR_DIM_PX) {
                val scale = MAX_AVATAR_DIM_PX.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also { scaled = it }
            } else {
                decoded
            }

            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val output = ByteArrayOutputStream(16 * 1024)
            if (targetBitmap.compress(format, WEBP_QUALITY, output)) {
                output.toByteArray().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } finally {
            if (scaled != null && scaled !== decoded && !scaled!!.isRecycled) {
                scaled!!.recycle()
            }
            if (!decoded.isRecycled) {
                decoded.recycle()
            }
        }
    }
}
