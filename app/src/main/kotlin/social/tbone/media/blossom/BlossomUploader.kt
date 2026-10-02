package social.tbone.media.blossom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import social.tbone.account.signer.NostrSigner
import social.tbone.di.ImageClientProvider
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Uploads media to Blossom servers (BUD-01/BUD-02 authorization via BUD-11).
 *
 * ### Server selection (unchanged user-visible behaviour)
 *  - a server chosen explicitly on the compose screen wins,
 *  - otherwise the user's default server (Settings → Blossom),
 *  - otherwise a random member of the enabled pool.
 *
 * ### What happens after a failure (changed)
 * The old code only ever tried *one* fallback server, and only after the
 * preferred/default had failed. Now every enabled server is a failover
 * candidate, so one dead or full server can no longer kill a post.
 *
 * ### Signing cost
 * The whole candidate pool is authorized by a **single** kind 24242 token
 * (multiple BUD-11 `server` tags), so an external signer (Amber/nsecBunker)
 * prompts the user exactly once per upload regardless of how many servers we
 * end up trying. Without that, failover would mean a fresh signing prompt per
 * server.
 */
@Singleton
class BlossomUploader @Inject constructor(
    private val appSettings: AppSettings,
) {

    /**
     * Test seam — lets unit tests drive the uploader against a MockWebServer
     * without touching the process-wide media client. Never set in app code.
     */
    internal var clientOverride: OkHttpClient? = null

    /** Cached derived client; rebuilt whenever the shared media client changes (Tor toggle). */
    @Volatile private var derivedFrom: OkHttpClient? = null
    @Volatile private var derived: OkHttpClient? = null

    /**
     * Uploads [file] to a member of the enabled pool and returns the public
     * URL of the stored blob.
     *
     * @param mime exact MIME type of [file]'s bytes — must describe the file as
     *   it exists on disk (servers validate it, and it becomes the blob's
     *   `Content-Type` forever after).
     * @param preferredServer server picked on the compose screen, or null.
     * @param alt human-readable description carried in the auth token.
     * @param extension file extension for the fallback URL when a server
     *   answers without a descriptor body.
     */
    suspend fun upload(
        file: File,
        mime: String,
        preferredServer: String?,
        signer: NostrSigner,
        alt: String = "Uploading media",
        extension: String = "",
    ): String = withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0) { "nothing to upload" }

        val pool = appSettings.blossomServers.value.toList()
        if (pool.isEmpty()) {
            error("no Blossom servers enabled — add one in Settings → Blossom")
        }

        val candidates = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = preferredServer,
            default = appSettings.blossomDefaultServer.value,
        )
        val hash = BlossomClient.sha256Hex(file)
        val size = file.length()

        // One token, scoped to every server we might try (BUD-11 `server` tags).
        val authHeader = BlossomAuth.header(
            signer = signer,
            type = BlossomAuth.TYPE_UPLOAD,
            alt = alt,
            hash = hash,
            size = size,
            servers = candidates,
        )

        Timber.d("blossom: uploading ${file.name} ($size B, $mime) sha=$hash to $candidates")

        val client = BlossomClient(clientOverride ?: uploadClient())
        val failures = mutableListOf<String>()

        for (server in candidates) {
            try {
                val result = client.upload(
                    file = file,
                    mime = mime,
                    serverBaseUrl = server,
                    authHeader = authHeader,
                    hash = hash,
                    extension = extension,
                )
                val url = result.url ?: BlossomServerUrl.blob(server, hash, extension)
                Timber.i("blossom: uploaded to $server -> $url")
                return@withContext url
            } catch (e: Exception) {
                // Keep going: a 413 on one server says nothing about the next.
                val host = BlossomServerUrl.domain(server).ifEmpty { server }
                val detail = (e as? BlossomException)?.shortReason ?: (e.message ?: "Upload failed.")
                failures += "$host: $detail"
                Timber.w(e, "blossom: upload failed on $server")
            }
        }

        throw BlossomUploadException(failures)
    }

    /**
     * The shared media client is configured for WebSockets
     * (`readTimeout(0)` = block forever), which would leave the compose screen
     * showing "…uploading" indefinitely if a server accepted the connection
     * and then stalled. Derive a copy with real timeouts — `newBuilder()` keeps
     * the proxy, so uploads still honour the user's Tor setting.
     */
    private fun uploadClient(): OkHttpClient {
        val base = ImageClientProvider.client
        derived?.let { if (derivedFrom === base) return it }
        val built = base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        derived = built
        derivedFrom = base
        return built
    }
}

/**
 * Thrown when every candidate server refused the upload. Aggregates the
 * per-server reasons so the user sees the full picture (e.g.
 * "nostr.download: file too large | ditto.pub: authorization rejected")
 * instead of whichever failure happened to come last.
 */
class BlossomUploadException(
    val failures: List<String>,
) : Exception(
    if (failures.isEmpty()) {
        "Upload failed."
    } else {
        "Upload failed on every server — ${failures.joinToString(" | ")}"
    },
)
