package social.tbone.ui.feed

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import social.tbone.nostr.Event

internal enum class ShareMediaKind { IMAGE, VIDEO, AUDIO }

internal data class ShareCardMedia(
    val url: String,
    val kind: ShareMediaKind,
    val mimeType: String? = null,
    val dimension: String? = null,
    val blurhash: String? = null,
    val thumbhash: String? = null,
    val posterUrl: String? = null,
)

/** Understands both extension-bearing URLs and NIP-92 `imeta` MIME hints. */
internal object ShareCardMediaParser {
    private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "heic")
    private val videoExtensions = setOf("mp4", "webm", "mov", "m4v", "mkv", "ogv", "3gp", "avi", "m3u8")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "weba", "aiff")

    fun parse(event: Event): List<ShareCardMedia> {
        val hints = LinkedHashMap<String, String?>()
        val metaHints = LinkedHashMap<String, ShareCardMedia>()
        event.tags.forEach { tag ->
            val fields = tag.drop(1).mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            val name = runCatching { tag.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
            if (name == "imeta") {
                val url = field(fields, "url") ?: return@forEach
                val mime = field(fields, "m") ?: field(fields, "mime")
                metaHints[url.trim()] = ShareCardMedia(
                    url = url.trim(),
                    kind = ShareMediaKind.IMAGE,
                    mimeType = mime?.trim(),
                    dimension = field(fields, "dim")?.trim(),
                    blurhash = field(fields, "blurhash")?.trim(),
                    thumbhash = field(fields, "thumbhash")?.trim(),
                    posterUrl = field(fields, "image")?.trim(),
                )
                hints[url.trim()] = mime?.trim()
            }
        }

        val orderedUrls = LinkedHashSet<String>()
        URL_REGEX.findAll(event.content).forEach { match ->
            orderedUrls += match.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
        }
        // URLs carried only in imeta are included too, preserving tag order.
        hints.keys.forEach(orderedUrls::add)

        return orderedUrls.mapNotNull { url ->
            val mime = hints[url]
            val kind = kindFor(url, mime) ?: return@mapNotNull null
            val meta = metaHints[url]
            ShareCardMedia(
                url = url,
                kind = kind,
                mimeType = mime,
                dimension = meta?.dimension,
                blurhash = meta?.blurhash,
                thumbhash = meta?.thumbhash,
                posterUrl = meta?.posterUrl,
            )
        }
    }

    fun stripMediaUrls(content: String, media: List<ShareCardMedia>): String {
        var text = content
        media.forEach { item -> text = text.replace(item.url, "") }
        return text
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun field(fields: List<String>, name: String): String? {
        val prefix = "$name "
        fields.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.let { return it.substringAfter(' ').trim().takeIf(String::isNotEmpty) }
        val index = fields.indexOfFirst { it.equals(name, ignoreCase = true) }
        return fields.getOrNull(index + 1)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun kindFor(url: String, mimeType: String?): ShareMediaKind? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("image/")) return ShareMediaKind.IMAGE
        if (mime.startsWith("video/")) return ShareMediaKind.VIDEO
        if (mime.startsWith("audio/")) return ShareMediaKind.AUDIO
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in imageExtensions -> ShareMediaKind.IMAGE
            in videoExtensions -> ShareMediaKind.VIDEO
            in audioExtensions -> ShareMediaKind.AUDIO
            else -> null
        }
    }
}

/** Event-aware variant of the normal note parser; recognizes no-extension Blossom media via imeta. */
fun parseEventContent(event: Event): ParsedContent {
    val media = ShareCardMediaParser.parse(event)
    val text = ShareCardMediaParser.stripMediaUrls(event.content, media)
    val ordinary = parseNoteContent(text)
    fun MediaMetaOf(item: ShareCardMedia) = MediaMeta(
        mime = item.mimeType,
        dimension = item.dimension,
        blurhash = item.blurhash,
        thumbhash = item.thumbhash,
        image = item.posterUrl,
    )
    val fromTagsAndUrls = media.map { item ->
        when (item.kind) {
            ShareMediaKind.IMAGE -> MediaItem.Image(item.url, MediaMetaOf(item))
            ShareMediaKind.VIDEO -> MediaItem.Video(item.url, MediaMetaOf(item))
            ShareMediaKind.AUDIO -> MediaItem.Audio(item.url)
        }
    }
    val combined = (fromTagsAndUrls + ordinary.mediaItems).distinctBy {
        when (it) {
            is MediaItem.Image -> it.url
            is MediaItem.Video -> it.url
            is MediaItem.Audio -> it.url
        }
    }
    return ParsedContent(ordinary.text, combined)
}
