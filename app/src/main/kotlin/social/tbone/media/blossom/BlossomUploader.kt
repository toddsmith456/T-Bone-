package social.tbone.media.blossom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import social.tbone.settings.OrbotHelper
import social.tbone.settings.OrbotStatus
import social.tbone.nostr.relay.RelayConnection
import okhttp3.OkHttpClient
import social.tbone.account.signer.NostrSigner
import social.tbone.lists.PublishedRelayLists
import social.tbone.nostr.EventKind
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
    @ApplicationContext private val context: Context,
    private val accountRepository: social.tbone.account.AccountRepository,
    private val relayListFetcher: social.tbone.lists.RelayListFetcher,
) {

    /**
     * Test seam — lets unit tests drive the uploader against a MockWebServer
     * without touching the process-wide media client. Never set in app code.
     */
    internal var clientOverride: OkHttpClient? = null

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
        appSettings.initialized.first { it }

        // Beaver Mode: the online (published) list is the authority, re-read on
        // every upload, so a change made from another client takes effect on the
        // very next attachment. The local list is ignored entirely in this mode.
        // A failed fetch falls back to the local list rather than failing the
        // upload: losing media because a relay was slow would be worse than using
        // a slightly stale server set.
        val (pool, beaverSource) = if (appSettings.blossomBeaverMode.value) {
            val online = fetchPublishedServers()
            if (online.isEmpty()) {
                Timber.w("blossom: beaver mode found no published servers; using local list")
                appSettings.blossomServers.value.toList() to "local (published list empty)"
            } else {
                online to "published"
            }
        } else {
            appSettings.blossomServers.value.toList() to "local"
        }

        val candidates = BlossomServerPool.orderedCandidates(
            pool = pool,
            preferred = preferredServer,
            default = appSettings.blossomDefaultServer.value.takeIf { beaverSource == "local" },
        )
        if (candidates.isEmpty()) {
            error("no Blossom servers enabled — add one in Settings → Blossom")
        }
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

        val failures = mutableListOf<String>()

        for (server in candidates) {
            try {
                val useTor = appSettings.torEnabled.value
                val transportEpoch = social.tbone.di.TorCallPolicy.guard.snapshot(useTor)
                val client = BlossomClient(clientOverride ?: uploadClient(useTor))
                val result = client.upload(
                    file = file,
                    mime = mime,
                    serverBaseUrl = server,
                    authHeader = authHeader,
                    hash = hash,
                    extension = extension,
                    expectedTor = if (clientOverride == null) useTor else null,
                    expectedTorEpoch = if (clientOverride == null) transportEpoch else null,
                )
                val url = result.url ?: BlossomServerUrl.blob(server, hash, extension)
                Timber.i("blossom: uploaded to $server -> $url")
                return@withContext url
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
     * Reads the account's published Blossom server list (BUD-03, kind 10063).
     *
     * Returns an empty list on any failure — callers decide what to fall back to.
     * This is a network read on the upload path, which is exactly what Beaver
     * Mode asks for; it is only called when that mode is on.
     */
    private suspend fun fetchPublishedServers(): List<String> = runCatching {
        val account = accountRepository.activeAccount.first() ?: return@runCatching emptyList()
        val result = relayListFetcher.fetchLatest(
            pubkey = account.pubkey,
            kind = EventKind.BLOSSOM_SERVER_LIST,
            seedRelays = account.effectiveRelays(),
        )
        result.event?.let { PublishedRelayLists.blossomServers(it) }.orEmpty()
    }.getOrElse { error ->
        Timber.w(error, "blossom: could not read the published server list")
        emptyList()
    }

    /** Build a current-mode client with finite upload timeouts. */
    private suspend fun uploadClient(useTor: Boolean): OkHttpClient {
        val proxy = if (useTor) OrbotHelper.getStatus(context) as? OrbotStatus.InstalledAndConnected else null
        if (shouldBlockUploadPreflight(useTor, appSettings.torKillSwitch.value, proxy != null)) {
            error("Orbot is not connected; upload blocked by the pre-upload check")
        }
        // Resolve from current settings at request time, not from a potentially
        // stale asynchronously updated process-wide client. Never fall back to
        // a direct client while Tor is enabled.
        return RelayConnection.buildClient(useTor = useTor,
            proxyType = proxy?.proxyType ?: java.net.Proxy.Type.HTTP,
            torPort = proxy?.port ?: 8118).newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
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

/** This check is a preflight, not continuous monitoring or a global kill switch. */
internal fun shouldBlockUploadPreflight(useTor: Boolean, checkEnabled: Boolean, orbotConnected: Boolean): Boolean =
    useTor && checkEnabled && !orbotConnected
