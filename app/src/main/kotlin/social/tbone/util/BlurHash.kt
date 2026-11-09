package social.tbone.util

import android.graphics.Bitmap
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * BlurHash encode/decode (the compact blurred-preview codes carried in
 * NIP-92 `imeta` tags). The core works on raw ARGB pixel arrays so it is
 * unit-testable without Android bitmaps; [decodeToBitmap] / [encodeBitmap]
 * are thin wrappers. Decoding is meant to happen at thumbnail size
 * (≤ ~100 px) off the main thread — at that size it costs almost nothing.
 */
object BlurHash {
    private const val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    /** Decoded pixels plus dimensions. */
    class Decoded(val pixels: IntArray, val width: Int, val height: Int)

    fun isValid(hash: String?): Boolean {
        if (hash.isNullOrBlank() || hash.length < 6) return false
        if (hash.any { it !in ALPHABET }) return false
        val sizeFlag = decode83(hash, 0, 1)
        val numX = sizeFlag % 9 + 1
        val numY = sizeFlag / 9 + 1
        return hash.length == 4 + 2 * numX * numY
    }

    /** Decodes [hash] into a [width]×[height] ARGB pixel array, or null if invalid. */
    fun decode(hash: String?, width: Int, height: Int): Decoded? {
        if (!isValid(hash) || width <= 0 || height <= 0) return null
        val h = hash!!
        val sizeFlag = decode83(h, 0, 1)
        val numX = sizeFlag % 9 + 1
        val numY = sizeFlag / 9 + 1
        val maxAc = (decode83(h, 1, 2) + 1) / 166f

        val colors = Array(numX * numY) { i ->
            if (i == 0) {
                val dc = decode83(h, 2, 6)
                floatArrayOf(
                    srgbToLinear(((dc shr 16) and 255) / 255f),
                    srgbToLinear(((dc shr 8) and 255) / 255f),
                    srgbToLinear((dc and 255) / 255f),
                )
            } else {
                val ac = decode83(h, 4 + 2 * i, 4 + 2 * i + 2)
                val r = ac / (19 * 19)
                val g = (ac / 19) % 19
                val b = ac % 19
                floatArrayOf(
                    signedPow((r - 9) / 9f) * maxAc,
                    signedPow((g - 9) / 9f) * maxAc,
                    signedPow((b - 9) / 9f) * maxAc,
                )
            }
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    for (i in 0 until numX) {
                        // DCT-II centre-sampled basis: with plain x/width the
                        // basis does not sum to zero, so even a solid colour
                        // would decode with brightened/darkened corners.
                        val basis = (cos(Math.PI * i * (x + 0.5) / width) *
                            cos(Math.PI * j * (y + 0.5) / height)).toFloat()
                        val c = colors[j * numX + i]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or
                    (linearToSrgb(r) shl 16) or
                    (linearToSrgb(g) shl 8) or
                    linearToSrgb(b)
            }
        }
        return Decoded(pixels, width, height)
    }

    /** Encodes ARGB [pixels] ([width]×[height]) into a BlurHash string. */
    fun encode(pixels: IntArray, width: Int, height: Int, numX: Int = 4, numY: Int = 3): String {
        require(pixels.size >= width * height && width > 0 && height > 0)
        val components = Array(numX * numY) { idx ->
            val i = idx % numX
            val j = idx / numX
            var r = 0f
            var g = 0f
            var b = 0f
            val normalisation = if (i == 0 && j == 0) 1f else 2f
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val basis = (cos(Math.PI * i * (x + 0.5) / width) *
                        cos(Math.PI * j * (y + 0.5) / height)).toFloat()
                    val p = pixels[y * width + x]
                    r += basis * srgbToLinear(((p shr 16) and 255) / 255f)
                    g += basis * srgbToLinear(((p shr 8) and 255) / 255f)
                    b += basis * srgbToLinear((p and 255) / 255f)
                }
            }
            val scale = normalisation / (width * height)
            floatArrayOf(r * scale, g * scale, b * scale)
        }

        val dc = components[0]
        val ac = components.drop(1)
        val maxAcValue = ac.maxOfOrNull { c ->
            max(max(kotlin.math.abs(c[0]), kotlin.math.abs(c[1])), kotlin.math.abs(c[2]))
        } ?: 0f
        val quantMax = (maxAcValue * 166f - 0.5f).toInt().coerceIn(0, 82)
        val quantisedMax = (quantMax + 1) / 166f

        val out = StringBuilder()
        out.append(encode83((numX - 1) + (numY - 1) * 9, 1))
        out.append(encode83(quantMax, 1))
        val dcInt = (linearToSrgb(dc[0]) shl 16) or (linearToSrgb(dc[1]) shl 8) or linearToSrgb(dc[2])
        out.append(encode83(dcInt, 4))
        for (c in ac) {
            val qr = quantiseAc(c[0], quantisedMax)
            val qg = quantiseAc(c[1], quantisedMax)
            val qb = quantiseAc(c[2], quantisedMax)
            out.append(encode83(qr * 361 + qg * 19 + qb, 2))
        }
        return out.toString()
    }

    fun decodeToBitmap(hash: String?, width: Int, height: Int): Bitmap? {
        val decoded = decode(hash, width, height) ?: return null
        return Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888).apply {
            setPixels(decoded.pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        }
    }

    /** Encodes a bitmap, downscaling first so the DCT stays cheap. */
    fun encodeBitmap(bitmap: Bitmap, numX: Int = 4, numY: Int = 3): String? = runCatching {
        val maxDim = 32
        val scale = min(1f, maxDim.toFloat() / max(bitmap.width, bitmap.height))
        val w = max(1, (bitmap.width * scale).toInt())
        val h = max(1, (bitmap.height * scale).toInt())
        val small = if (w == bitmap.width && h == bitmap.height) bitmap
            else Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        encode(pixels, w, h, numX, numY)
    }.getOrNull()

    private fun quantiseAc(value: Float, maxAc: Float): Int {
        if (maxAc <= 0f) return 9
        val scaled = signedPow(value / maxAc) * 9f + 9.5f
        return scaled.toInt().coerceIn(0, 18)
    }

    private fun signedPow(v: Float): Float = sign(v) * v * v

    private fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(c: Float): Int {
        val v = c.coerceIn(0f, 1f)
        val srgb = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }

    private fun decode83(s: String, start: Int, end: Int): Int {
        var result = 0
        for (i in start until end) result = result * 83 + ALPHABET.indexOf(s[i]).coerceAtLeast(0)
        return result
    }

    private fun encode83(value: Int, length: Int): String {
        val chars = CharArray(length)
        var v = value
        for (i in length - 1 downTo 0) {
            chars[i] = ALPHABET[v % 83]
            v /= 83
        }
        return String(chars)
    }
}
