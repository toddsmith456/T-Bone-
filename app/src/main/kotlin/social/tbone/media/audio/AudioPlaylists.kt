package social.tbone.media.audio

import social.tbone.media.MimeTypes
import social.tbone.notes.BookmarkFolderUi
import social.tbone.nostr.Event
import social.tbone.util.HTTP_URL_REGEX
import social.tbone.util.trimUrlPunctuation

/**
 * Audio playlists are ordinary NIP-51 kind-30003 bookmark sets. A title prefix
 * distinguishes them from a user's general-purpose bookmark folders without
 * inventing a new event kind or breaking interoperability.
 */
object AudioPlaylists {
    const val TITLE_PREFIX = "T-Bone Music / "
    private const val D_TAG_PREFIX = "t-bone-music-"

    fun title(name: String): String = TITLE_PREFIX + name.trim()

    fun displayName(title: String): String {
        val prefixMatches = title.startsWith(TITLE_PREFIX, ignoreCase = true)
        return if (prefixMatches) title.drop(TITLE_PREFIX.length).ifBlank { title } else title
    }

    fun isPlaylist(folder: BookmarkFolderUi): Boolean =
        folder.isOnline && (
            folder.name.startsWith(TITLE_PREFIX, ignoreCase = true) ||
                folder.dTag?.startsWith(D_TAG_PREFIX, ignoreCase = true) == true
            )
}

/** Extracts audio URLs from visible content and NIP-92 imeta hints, in event order. */
fun audioUrls(event: Event): List<String> {
    val mimeByUrl = LinkedHashMap<String, String>()
    event.parsedTags.asSequence()
        .filter { it.name == "imeta" }
        .forEach { tag ->
            val fields = tag.values.drop(1)
            fun field(name: String): String? {
                val prefix = "$name "
                fields.firstOrNull { it.startsWith(prefix, ignoreCase = true) }
                    ?.let { return it.substringAfter(' ').trim().takeIf(String::isNotEmpty) }
                val index = fields.indexOfFirst { it.equals(name, ignoreCase = true) }
                return fields.getOrNull(index + 1)?.trim()?.takeIf(String::isNotEmpty)
            }
            val url = field("url") ?: return@forEach
            field("m")?.let { mimeByUrl[url] = it.substringBefore(';').trim() }
        }

    val ordered = LinkedHashSet<String>()
    HTTP_URL_REGEX.findAll(event.content).forEach { match ->
        ordered += trimUrlPunctuation(match.value)
    }
    mimeByUrl.keys.forEach(ordered::add)

    return ordered.filter { url ->
        val mime = mimeByUrl[url]
        if (mime != null && MimeTypes.isAudio(mime)) return@filter true
        val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        MimeTypes.mimeForExtension(extension)?.let(MimeTypes::isAudio) == true
    }
}

fun audioUrl(event: Event): String? = audioUrls(event).firstOrNull()

/** Display title from the note prose, falling back to its attached filename. */
fun audioTrackTitle(event: Event, url: String): String {
    val prose = event.content
        .replace(url, " ")
        .replace(Regex("(?i)#tbonemusic\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (prose.isNotBlank()) return prose.take(100)
    val filename = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    return filename.takeIf { it.isNotBlank() } ?: "untitled audio"
}
