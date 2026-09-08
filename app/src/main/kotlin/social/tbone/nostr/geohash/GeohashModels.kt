package social.tbone.nostr.geohash

/**
 * A saved geohash channel shown in the messenger's channel list.
 * Persisted in AppSettings (order = display order; pinned sorts to the top).
 */
data class GeohashChannelEntry(
    val code: String,
    val pinned: Boolean = false,
    val sortOrder: Double = 0.0,
    /** Pinned to the home screen strip — a background monitor keeps its unread count. */
    val pinnedToHome: Boolean = false,
)

/** Serializes channels to the stored line format "code:pinned:pinnedToHome" per line. */
object GeohashChannelStore {
    fun encode(entries: List<GeohashChannelEntry>): String =
        entries.joinToString("\n") {
            "${it.code}:${if (it.pinned) "1" else "0"}:${if (it.pinnedToHome) "1" else "0"}"
        }

    fun decode(raw: String): List<GeohashChannelEntry> =
        raw.split("\n")
            .mapNotNull { line ->
                val parts = line.split(":")
                if (parts.size < 2 || parts[0].isBlank()) null
                else GeohashChannelEntry(
                    code = parts[0].trim(),
                    pinned = parts.getOrNull(1) == "1",
                    pinnedToHome = parts.getOrNull(2) == "1",
                )
            }
}
