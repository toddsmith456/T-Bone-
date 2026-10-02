package social.tbone.media

/**
 * MIME ↔ extension mapping used when handing media to Blossom.
 *
 * The extension only matters for the fallback blob URL (BUD-01 requires
 * `GET /<sha256>` to work with *or* without it); the MIME type is the part
 * servers actually validate and store, so it is always taken from the real
 * bytes on disk rather than from the picker.
 */
object MimeTypes {

    /** File extension for [mime] without the dot, or "" when unknown. */
    fun extensionFor(mime: String): String = when (mime.lowercase().substringBefore(';').trim()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        "video/3gpp" -> "3gp"
        "video/x-matroska" -> "mkv"
        "audio/mpeg" -> "mp3"
        "audio/ogg" -> "ogg"
        else -> ""
    }

    /** True when [mime] is a video type. */
    fun isVideo(mime: String): Boolean = mime.lowercase().startsWith("video/")
}
