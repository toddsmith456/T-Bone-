package social.tbone.ui.toolbox.geohash

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/** Bitchat's February 2026 iOS-compatible DJB2 hue, seeded by Nostr identity. */
internal fun geohashPeerHue(pubkey: String): Float {
    var hash = 5381UL
    for (byte in ("nostr:" + pubkey.lowercase()).toByteArray(Charsets.UTF_8)) {
        hash = ((hash shl 5) + hash) + byte.toUByte().toULong()
    }
    var hue = (hash % 360UL).toDouble() / 360.0
    if (abs(hue - 30.0 / 360.0) < 0.05) hue = (hue + 0.12) % 1.0
    return (hue * 360).toFloat()
}

internal fun geohashPeerColor(pubkey: String, dark: Boolean) = Color.hsv(
    geohashPeerHue(pubkey), if (dark) .50f else .70f, if (dark) .85f else .35f,
)
internal val GeohashOwnColor = Color(0xFFFF9500)
