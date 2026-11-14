package social.tbone.ai

import android.graphics.Bitmap

/**
 * 64-bit difference hash (dHash) for fast perceptual deduplication of reposted /
 * re-encoded Nostr images across different Blossom or CDN URLs.
 *
 * Kept in RAM only (never persisted to disk).
 */
object PerceptualHash {
    const val WIDTH = 9
    const val HEIGHT = 8

    /** Compute a 64-bit dHash from an Android [Bitmap] by scaling to 9x8. */
    fun computeDHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, WIDTH, HEIGHT, true)
        return try {
            val pixels = IntArray(WIDTH * HEIGHT)
            small.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            computeDHashFromArgb(pixels)
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    /** Pure-JVM core helper operating on a 9x8 ARGB pixel array (unit-testable without Robolectric). */
    fun computeDHashFromArgb(argbPixels9x8: IntArray): Long {
        require(argbPixels9x8.size == WIDTH * HEIGHT) {
            "Expected ${WIDTH * HEIGHT} ARGB pixels, got ${argbPixels9x8.size}"
        }
        var hash = 0L
        var bit = 0
        for (y in 0 until HEIGHT) {
            val rowOffset = y * WIDTH
            for (x in 0 until (WIDTH - 1)) {
                val left = luma(argbPixels9x8[rowOffset + x])
                val right = luma(argbPixels9x8[rowOffset + x + 1])
                if (left > right) {
                    hash = hash or (1L shl bit)
                }
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luma(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        // Integer approximation of Rec.601 luma (0..255).
        return (r * 77 + g * 150 + b * 29) ushr 8
    }
}
