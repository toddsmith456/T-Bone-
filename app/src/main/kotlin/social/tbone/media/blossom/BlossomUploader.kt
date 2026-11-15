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
 * ### Server selection
 *  - When a server is chosen explicitly on the Write tab (`preferredServer != null`),
 *    it is a hard override: Settings rules (Beaver Mode, default server, enabled pool)
 *    are bypassed, the BUD-11 auth token is scoped strictly to that single server,
 *    and the upload never silently fails over to a different server.
 *  - Otherwise (auto / Settings rules), Beaver Mode's published list or the local
 *    default + enabled pool is used, with automatic failover across every server in
 *    that pool under a single kind 24242 token (multiple BUD-11 `server` tags) so
 *    external signers prompt at most once per upload.
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
     * Uploads [file] and returns the public URL of the stored blob.
     *
     * @param mime exact MIME type of [file]'s bytes — must describe the file as
     *   it exists on disk (servers validate it, and it becomes the blob's
     *   `Content-Type` forever after).
     * @param preferredServer explicit server override picked on the Write tab,
     *   or null to follow Settings rules.
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

        val candidates = resolveUploadCandidates(
            preferredServer = preferredServer,
            beaverMode = appSettings.blossomBeaverMode.value,
            localPool = appSettings.blossomServers.value.toList(),
            defaultServer = appSettings.blossomDefaultServer.value,
            fetchPublished = { fetchPublishedServers() },
        )

        executeBlossomUpload(
            file = file,
            mime = mime,
            candidates = candidates,
            signer = signer,
            alt = alt,
            extension = extension,
            resolveTransport = {
                val useTor = appSettings.torEnabled.value
                val transportEpoch = social.tbone.di.TorCallPolicy.guard.snapshot(useTor)
                val override = clientOverride
                BlossomUploadTransport(
                    client = override ?: uploadClient(useTor),
                    expectedTor = if (override == null) useTor else null,
                    expectedTorEpoch = if (override == null) transportEpoch else null,
                )
            },
        )
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

internal data class BlossomUploadTransport(
    val client: OkHttpClient,
    val expectedTor: Boolean? = null,
    val expectedTorEpoch: Long? = null,
)

/**
 * Resolves the exact ordered server list for one upload.
 *
 * When [preferredServer] is non-blank (explicit Write-tab selection), Beaver Mode
 * and Settings rules are bypassed completely — [fetchPublished] is never called
 * and ONLY that server is returned.
 */
internal suspend fun resolveUploadCandidates(
    preferredServer: String?,
    beaverMode: Boolean,
    localPool: List<String>,
    defaultServer: String?,
    fetchPublished: suspend () -> List<String>,
    shuffle: (List<String>) -> List<String> = { it.shuffled() },
): List<String> {
    val explicit = preferredServer?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() }
    if (explicit != null) {
        return BlossomServerPool.orderedCandidates(
            pool = emptyList(),
            preferred = explicit,
            default = null,
            shuffle = shuffle,
        )
    }

    // Beaver Mode: the online (published) list is the authority, re-read on
    // every upload, so a change made from another client takes effect on the
    // very next attachment. A failed/empty fetch falls back to the local list.
    val (pool, useLocalDefault) = if (beaverMode) {
        val online = fetchPublished()
        if (online.isEmpty()) {
            Timber.w("blossom: beaver mode found no published servers; using local list")
            localPool to false
        } else {
            online to false
        }
    } else {
        localPool to true
    }

    return BlossomServerPool.orderedCandidates(
        pool = pool,
        preferred = null,
        default = defaultServer.takeIf { useLocalDefault },
        shuffle = shuffle,
    )
}

internal suspend fun executeBlossomUpload(
    file: File,
    mime: String,
    candidates: List<String>,
    signer: NostrSigner,
    alt: String = "Uploading media",
    extension: String = "",
    resolveTransport: suspend () -> BlossomUploadTransport,
): String {
    if (candidates.isEmpty()) {
        error("no Blossom servers enabled — add one in Settings → Blossom")
    }
    val hash = BlossomClient.sha256Hex(file)
    val size = file.length()

    // One token, scoped strictly to `candidates` (single server tag for an
    // explicit Write-tab override; pool tags for Settings-driven failover).
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
            val transport = resolveTransport()
            val client = BlossomClient(transport.client)
            val result = client.upload(
                file = file,
                mime = mime,
                serverBaseUrl = server,
                authHeader = authHeader,
                hash = hash,
                extension = extension,
                expectedTor = transport.expectedTor,
                expectedTorEpoch = transport.expectedTorEpoch,
            )
            val url = result.url ?: BlossomServerUrl.blob(server, hash, extension)
            Timber.i("blossom: uploaded to $server -> $url")
            return url
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep going when multiple failover candidates exist.
            val host = BlossomServerUrl.domain(server).ifEmpty { server }
            val detail = (e as? BlossomException)?.shortReason ?: (e.message ?: "Upload failed.")
            failures += "$host: $detail"
            Timber.w(e, "blossom: upload failed on $server")
        }
    }

    throw BlossomUploadException(failures)
}

/**
 * Thrown when upload failed on the selected server (or every candidate server
 * in failover mode). Aggregates per-server reasons so the user sees why the
 * upload was refused.
 */
class BlossomUploadException(
    val failures: List<String>,
) : Exception(
    when (failures.size) {
        0 -> "Upload failed."
        1 -> failures.first()
        else -> "Upload failed on every server — ${failures.joinToString(" | ")}"
    },
)

/** This check is a preflight, not continuous monitoring or a global kill switch. */
internal fun shouldBlockUploadPreflight(useTor: Boolean, checkEnabled: Boolean, orbotConnected: Boolean): Boolean =
    useTor && checkEnabled && !orbotConnected
