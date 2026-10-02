package social.tbone.media.blossom

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Blob Descriptor every Blossom upload/mirror endpoint answers with
 * (BUD-02). Only the documented fields are modelled; [BlossomJson] ignores
 * anything newer a server may add (BUD-05 `ox`, BUD-08 `nip94`, …) so a
 * chatty server can never break the upload path.
 */
@Serializable
data class BlossomUploadResult(
    /** Public URL of the `GET /<sha256>` endpoint, usually with a file extension. */
    val url: String? = null,
    /** Hex-encoded SHA-256 of the stored blob (differs from the input on BUD-05 `/media`). */
    val sha256: String? = null,
    /** Size of the stored blob in bytes. */
    val size: Long? = null,
    /** MIME type the server assigned. */
    val type: String? = null,
    /** Unix timestamp of the upload. */
    val uploaded: Long? = null,
    /** BUD-05: hash of the *original* blob when the server optimised it. */
    val ox: String? = null,
    val magnet: String? = null,
    val infohash: String? = null,
    val ipfs: String? = null,
)

/**
 * Lenient parser for blob descriptors. Deliberately separate from
 * [social.tbone.nostr.NostrJson]: descriptor parsing must never fail hard —
 * a server that answers with a bare URL string, a partial object or extra
 * fields still yields a usable upload.
 */
object BlossomJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Parses [body] into a [BlossomUploadResult].
     *
     * Handles the three shapes seen in the wild:
     *  1. the BUD-02 object `{"url":…,"sha256":…}`,
     *  2. a bare quoted URL `"https://…/<sha>"`,
     *  3. anything else → null (the caller then derives the URL from the hash).
     */
    fun parse(body: String?): BlossomUploadResult? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) {
            // Some servers answer with just the URL as a JSON string.
            val bare = text.trim('"').trim()
            return if (bare.startsWith("http")) BlossomUploadResult(url = bare) else null
        }
        return runCatching { json.decodeFromString(BlossomUploadResult.serializer(), text) }.getOrNull()
    }
}
