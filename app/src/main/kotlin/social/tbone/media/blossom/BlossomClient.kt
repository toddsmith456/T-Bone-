package social.tbone.media.blossom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import social.tbone.media.blossom.BlossomServerUrl.blob
import social.tbone.media.blossom.BlossomServerUrl.upload
import java.io.File
import java.security.MessageDigest

/**
 * Raised for any non-2xx Blossom answer. Carries the server's own
 * `X-Reason`/body text so the UI can say *why* an upload was refused
 * (filesize cap, banned hash, missing auth, …) instead of a bare status code.
 */
class BlossomException(
    val server: String,
    val status: Int,
    val reason: String?,
    cause: Throwable? = null,
) : Exception(buildMessage(server, status, reason), cause) {

    /**
     * The reason without the server prefix — lets callers aggregate several
     * servers' failures without repeating the host name twice.
     */
    val shortReason: String = describe(status, reason)

    companion object {
        private fun describe(status: Int, reason: String?): String {
            val detail = reason?.takeIf { it.isNotBlank() }
            val base = when (status) {
                401, 403 -> "authorization rejected"
                402 -> "payment required for this file"
                404 -> "no /upload endpoint"
                405 -> "does not accept PUT /upload"
                413 -> "file too large"
                415 -> "file type not accepted"
                429 -> "rate limited — try again shortly"
                else -> "refused the upload (HTTP $status)"
            }
            return if (detail != null) "$base — $detail" else base
        }

        private fun buildMessage(server: String, status: Int, reason: String?): String {
            val host = BlossomServerUrl.domain(server).ifEmpty { server }
            return "$host: ${describe(status, reason)}"
        }
    }
}

/**
 * Transport for the Blossom HTTP API (BUD-01/BUD-02). Holds no app state and
 * performs no signing: it takes an already-built `Authorization` header, which
 * keeps it fully unit-testable and keeps policy decisions in
 * [BlossomUploader].
 *
 * The upload body is streamed straight from disk, so a 200 MB video never has
 * to fit in memory, and `Content-Length` is always exact (servers answer 411
 * when it is missing).
 */
class BlossomClient(private val client: OkHttpClient) {

    /**
     * `PUT <server>/upload` — BUD-02.
     *
     * @param authHeader full `Authorization` value (`Nostr <base64>`).
     * @param hash lowercase hex SHA-256 of [file]; also sent as `X-SHA-256`
     *   (BUD-06) because servers that bind the body to the token's `x` tag
     *   reject the upload before reading it when the header is absent.
     * @return the blob descriptor, or — when a server answers 2xx without a
     *   parseable body — a descriptor whose [BlossomUploadResult.url] is
     *   derived from `X-SHA-256` in the standard `<server>/<sha256>` shape.
     */
    suspend fun upload(
        file: File,
        mime: String,
        serverBaseUrl: String,
        authHeader: String,
        hash: String,
        extension: String = "",
    ): BlossomUploadResult = withContext(Dispatchers.IO) {
        val endpoint = upload(serverBaseUrl)
        val body = fileBody(file, mime)
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", authHeader)
            .header("Content-Type", mime)
            .header(BlossomServerUrl.X_SHA_256_HEADER, hash.lowercase())
            .put(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = runCatching { response.body?.string() }.getOrNull()
            if (!response.isSuccessful) {
                throw BlossomException(
                    server = serverBaseUrl,
                    status = response.code,
                    reason = response.header(BlossomServerUrl.REASON_HEADER)
                        ?: text?.takeIf { it.isNotBlank() && it.length < 300 },
                )
            }
            val parsed = BlossomJson.parse(text)
            val normalizedHash = (parsed?.sha256 ?: hash).lowercase()
            when {
                // Use the server's own URL: it carries the file extension the
                // server chose (BUD-01 requires GET to work with OR without it,
                // but the extension is what other clients display/link).
                parsed?.url != null -> parsed
                else -> BlossomUploadResult(
                    url = blob(serverBaseUrl, normalizedHash, extension),
                    sha256 = normalizedHash,
                    size = parsed?.size ?: file.length(),
                    type = parsed?.type ?: mime,
                )
            }
        }
    }

    /**
     * `HEAD /<sha256>` — BUD-01 "does this server already hold the blob?".
     * Returns false on any network hiccup rather than throwing: it is only a
     * cheap optimisation.
     */
    suspend fun has(hash: String, serverBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(blob(serverBaseUrl, hash)).head().build()
        try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** `DELETE /<sha256>` — BUD-02. Requires a `t=delete` token. */
    suspend fun delete(
        hash: String,
        serverBaseUrl: String,
        authHeader: String,
        extension: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(blob(serverBaseUrl, hash, extension))
            .header("Authorization", authHeader)
            .delete()
            .build()
        client.newCall(request).execute().use { it.isSuccessful }
    }

    /**
     * A request body that streams [file] from disk without buffering it, while
     * still reporting an exact `contentLength` (BUD-02 requires `Content-Length`).
     */
    private fun fileBody(file: File, mime: String): RequestBody = object : RequestBody() {
        override fun contentType() = mime.toMediaType()
        override fun contentLength(): Long = file.length()
        override fun writeTo(sink: BufferedSink) {
            file.source().use { sink.writeAll(it) }
        }
    }

    companion object {
        /**
         * Streaming SHA-256 of [file], lower-case hex.
         *
         * Done in one pass over the file and reused for the `x` tag, the
         * `X-SHA-256` header and the fallback blob URL, so the bytes are read
         * exactly once no matter how large the file is.
         */
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
