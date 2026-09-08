package social.tbone.nostr.geohash

/**
 * Compact geohash encoder/decoder (base32, chars 0-9 b-z excluding a,i,l,o).
 * Matches the scheme Amethyst/Bitchat use for location channels.
 */
object Geohash {
    const val MAX_CHAR_PRECISION = 9

    private val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val BASE32_MAP = BASE32.withIndex().associate { it.value to it.index }

    fun encode(lat: Double, lon: Double, chars: Int = MAX_CHAR_PRECISION): String {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val result = StringBuilder()
        var bit = 0
        var ch = 0
        var even = true
        var lat = lat; var lon = lon
        while (result.length < chars) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = (ch shl 1) or 1; lonMin = mid } else { ch = ch shl 1; lonMax = mid }
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = (ch shl 1) or 1; latMin = mid } else { ch = ch shl 1; latMax = mid }
            }
            even = !even
            if (++bit == 5) {
                result.append(BASE32[ch])
                bit = 0; ch = 0
            }
        }
        return result.toString()
    }

    data class Decoded(val latitude: Double, val longitude: Double)

    /** Decodes to the cell's center. Returns null on invalid input. */
    fun decode(geohash: String): Decoded? = runCatching {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        var even = true
        for (c in geohash.lowercase()) {
            val cd = BASE32_MAP[c] ?: return null
            var mask = 16
            while (mask != 0) {
                if (even) {
                    val mid = (lonMin + lonMax) / 2
                    if (cd and mask != 0) lonMin = mid else lonMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (cd and mask != 0) latMin = mid else latMax = mid
                }
                even = !even
                mask = mask shr 1
            }
        }
        Decoded((latMin + latMax) / 2, (lonMin + lonMax) / 2)
    }.getOrNull()

    /** The 8 neighboring cells of [geohash]. */
    fun neighbors(geohash: String): List<String> {
        val center = decode(geohash) ?: return emptyList()
        val latStep = latStepFor(geohash.length)
        val lonStep = lonStepFor(geohash.length)
        val lat = center.latitude
        val lon = center.longitude
        return buildList {
            add(encode(lat + latStep, lon, geohash.length))
            add(encode(lat - latStep, lon, geohash.length))
            add(encode(lat, lon + lonStep, geohash.length))
            add(encode(lat, lon - lonStep, geohash.length))
            add(encode(lat + latStep, lon + lonStep, geohash.length))
            add(encode(lat - latStep, lon - lonStep, geohash.length))
            add(encode(lat + latStep, lon - lonStep, geohash.length))
            add(encode(lat - latStep, lon + lonStep, geohash.length))
        }
    }

    /** Height of a cell with [chars] characters. */
    private fun latStepFor(chars: Int): Double {
        val latBits = (chars * 5 + 1) / 2
        var latMin = -90.0; var latMax = 90.0
        for (k in 0 until latBits) { latMin = (latMin + latMax) / 2 }
        return latMax - latMin
    }

    /** Width of a cell with [chars] characters. */
    private fun lonStepFor(chars: Int): Double {
        val lonBits = (chars * 5) / 2
        var lonMin = -180.0; var lonMax = 180.0
        for (k in 0 until lonBits) { lonMin = (lonMin + lonMax) / 2 }
        return lonMax - lonMin
    }
}
