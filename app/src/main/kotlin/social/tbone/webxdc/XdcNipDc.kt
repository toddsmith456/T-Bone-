package social.tbone.webxdc

/**
 * A webxdc app published on Nostr (NIP-DC, the "Nostr Webxdc" draft Ditto
 * implements).
 *
 * Discovery uses kind 1063 (NIP-94 file metadata) with
 * `m = application/x-webxdc`, which is the only relay-indexable form:
 * `#m` is a plain tag filter. NIP-DC also allows attaching an `.xdc` to a
 * kind 1 note through `imeta`; [XdcNipDc.parseImeta] covers that for clients
 * that already have the note.
 */
data class XdcFeedApp(
    val eventId: String,
    val pubkey: String,
    val createdAt: Long,
    val name: String,
    val description: String?,
    val url: String,
    val iconUrl: String?,
    val uuid: String?,
    val sizeBytes: Long?,
    val sourceCodeUrl: String?,
)

object XdcNipDc {

    const val MIME = "application/x-webxdc"
    const val KIND_FILE_METADATA = 1063
    const val KIND_STATE_UPDATE = 4932
    const val KIND_REALTIME = 20932

    /**
     * Parses a kind 1063 file-metadata event. Returns null when the event is
     * not a webxdc app (wrong kind, wrong mime, missing http(s) url).
     */
    fun parseFileMetadata(
        kind: Int,
        eventId: String,
        pubkey: String,
        createdAt: Long,
        content: String,
        tags: List<List<String>>,
    ): XdcFeedApp? {
        if (kind != KIND_FILE_METADATA) return null
        val map = tagMap(tags)
        val mime = map["m"]?.firstOrNull()?.lowercase()
        val supported = mime == MIME || mime == "application/vnd.webxdc+zip"
        if (!supported) {
            // A present-but-different mime is authoritative. Only a *missing*
            // mime falls back to the .xdc extension, for sloppy publishers.
            if (mime != null) return null
            val url = map["url"]?.firstOrNull()
            if (url == null || !url.lowercase().substringBefore('?').endsWith(".xdc")) return null
        }
        val url = map["url"]?.firstOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return null

        val file = url.substringBefore('?').substringAfterLast('/')
        val alt = map["alt"]?.firstOrNull()
        val summary = map["summary"]?.firstOrNull()
        val name = alt?.replace(Regex("^Webxdc app:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: summary?.takeIf { it.isNotBlank() }
            ?: file.removeSuffix(".xdc").ifBlank { "webxdc app" }

        return XdcFeedApp(
            eventId = eventId,
            pubkey = pubkey,
            createdAt = createdAt,
            name = name,
            description = content.takeIf { it.isNotBlank() },
            url = url,
            iconUrl = map["thumb"]?.firstOrNull() ?: map["image"]?.firstOrNull(),
            uuid = map["webxdc"]?.firstOrNull(),
            sizeBytes = map["size"]?.firstOrNull()?.toLongOrNull(),
            sourceCodeUrl = map["source"]?.firstOrNull() ?: map["r"]?.firstOrNull(),
        )
    }

    /**
     * Parses a NIP-92 `imeta` attachment from any event (usually kind 1).
     * `imeta` entries are space-separated `key value` pairs.
     */
    fun parseImeta(
        eventId: String,
        pubkey: String,
        createdAt: Long,
        content: String,
        tags: List<List<String>>,
    ): XdcFeedApp? {
        val imeta = tags.firstOrNull { it.firstOrNull() == "imeta" } ?: return null
        val fields = HashMap<String, String>()
        for (part in imeta.drop(1)) {
            val space = part.indexOf(' ')
            if (space <= 0) continue
            fields[part.substring(0, space)] = part.substring(space + 1)
        }
        val mime = fields["m"]?.lowercase()
        val url = fields["url"] ?: return null
        val supported = mime == MIME || mime == "application/vnd.webxdc+zip" ||
            url.lowercase().substringBefore('?').endsWith(".xdc")
        if (!supported) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val file = url.substringBefore('?').substringAfterLast('/')
        return XdcFeedApp(
            eventId = eventId,
            pubkey = pubkey,
            createdAt = createdAt,
            name = file.removeSuffix(".xdc").ifBlank { "webxdc app" },
            description = content.takeIf { it.isNotBlank() },
            url = url,
            iconUrl = null,
            uuid = fields["webxdc"],
            sizeBytes = fields["size"]?.toLongOrNull(),
            sourceCodeUrl = null,
        )
    }

    private fun tagMap(tags: List<List<String>>): Map<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (tag in tags) {
            val key = tag.firstOrNull() ?: continue
            val value = tag.getOrNull(1) ?: continue
            out.getOrPut(key) { mutableListOf() }.add(value)
        }
        return out
    }
}
