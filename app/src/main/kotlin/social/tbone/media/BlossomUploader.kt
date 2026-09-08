package social.tbone.media

import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import social.tbone.account.signer.NostrSigner
import social.tbone.di.ImageClientProvider
import social.tbone.nostr.Event
import social.tbone.nostr.NostrJson
import social.tbone.nostr.UnsignedEvent
import social.tbone.settings.AppSettings
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Uploads media files to Blossom servers (NIP-98 authorized HTTP PUT).
 *
 * Server selection:
 *  - a server chosen explicitly on the compose screen wins,
 *  - otherwise the user's default server (set in Settings → Blossom),
 *  - otherwise a random server from the enabled pool — so if no server is
 *    specified the file lands on a random member of the pool.
 *
 * The upload itself goes through [ImageClientProvider] — the same OkHttp
 * client as image loading — so the Tor setting is honored for uploads too.
 */
@Singleton
class BlossomUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
) {

    /**
     * Uploads [file] to the pool (preferred → default → random order) and
     * returns the public https:// URL of the stored blob.
     */
    suspend fun upload(file: File, mime: String, preferredServer: String?, signer: NostrSigner): String =
        withContext(Dispatchers.IO) {
            val pool = appSettings.blossomServers.value.toList()
            if (pool.isEmpty()) error("no blossom servers enabled — add one in Settings → Blossom")

            val ordered = buildList {
                preferredServer?.let { if (it in pool) add(it) }
                appSettings.blossomDefaultServer.value?.let { if (it in pool && it != preferredServer) add(it) }
            }
            // Only try preferred + default + one fallback — avoid multiple signing prompts.
            val rest = pool.filter { it !in ordered }.shuffled().take(1)
            val attempts = (ordered + rest).distinct()

            var lastError: Exception? = null
            for (server in attempts) {
                try {
                    return@withContext uploadTo(server, file, mime, signer)
                } catch (e: Exception) {
                    lastError = e
                    Timber.w(e, "blossom upload failed on $server")
                }
            }
            throw lastError ?: IllegalStateException("no blossom servers available")
        }

    private suspend fun uploadTo(base: String, file: File, mime: String, signer: NostrSigner): String {
        val uploadUrl = "$base/upload"
        val auth = nip98Auth(uploadUrl, "PUT", signer)
        val sha = file.sha256Hex()

        val request = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Nostr $auth")
            .header("Content-Type", mime)
            .put(file.asRequestBody(mime.toMediaType()))
            .build()

        ImageClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("blossom $base rejected upload (HTTP ${response.code})")
            }
        }
        return "$base/$sha"
    }

    /** Builds a NIP-98 HTTP-auth event and base64-encodes it for the header. */
    private suspend fun nip98Auth(fullUrl: String, method: String, signer: NostrSigner): String {
        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = 27235,
            tags = buildList {
                add(buildJsonArray { add(JsonPrimitive("u")); add(JsonPrimitive(fullUrl)) })
                add(buildJsonArray { add(JsonPrimitive("method")); add(JsonPrimitive(method)) })
            },
            content = "",
        )
        val signed = signer.signEvent(unsigned).getOrThrow()
        val json = NostrJson.encodeToString(Event.serializer(), signed)
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun File.sha256Hex(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
