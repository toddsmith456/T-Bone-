package social.tbone.account.signer

import android.content.Intent
import timber.log.Timber
import androidx.core.net.toUri
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import social.tbone.nostr.Event
import social.tbone.nostr.UnsignedEvent

/**
 * NIP-55: delegates signing and encryption to Amber (or any compatible
 * external signer app) via Android intents.
 *
 * Intent flow:
 *   1. Build an intent with URI `nostrsigner:<payload>` and extras
 *   2. Post to [AmberSignerBridge] and suspend
 *   3. MainActivity launches the intent via ActivityResultLauncher
 *   4. Amber returns the result; bridge completes the deferred
 *   5. This signer resumes and returns the result
 */
class AmberSigner(
    override val pubkey: String,
    private val bridge: AmberSignerBridge,
    private val callerPackage: String,
) : NostrSigner {

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> {
        val withPubkey = event.copy(pubkey = pubkey)
        val id = withPubkey.computeId()

        // NIP-55: Amber requires id pre-computed and sig="" present.
        // Amber reads event JSON from the URI (URL-decoded), not from an "event" extra.
        val eventJson = buildJsonObject {
            put("id", id)
            put("pubkey", withPubkey.pubkey)
            put("created_at", withPubkey.createdAt)
            put("kind", withPubkey.kind)
            put("tags", buildJsonArray { withPubkey.tags.forEach { add(it) } })
            put("content", withPubkey.content)
            put("sig", "")
        }.toString()

        Timber.d("sign_event JSON (first 500): ${eventJson.take(500)}")

        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:$eventJson".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            `package` = AMBER_PACKAGE
            putExtra("type", "sign_event")
            putExtra("current_user", pubkey)
            putExtra("package", callerPackage)
            putExtra("id", id)
        }

        return bridge.request(intent).mapCatching { result ->
            val signed = if (result.startsWith("{")) {
                val parsed = Event.fromJson(result)
                // Guard against a compromised signer substituting event fields.
                check(parsed.pubkey == withPubkey.pubkey) { "Amber substituted pubkey" }
                check(parsed.kind == withPubkey.kind) { "Amber substituted kind" }
                check(parsed.content == withPubkey.content) { "Amber substituted content" }
                check(parsed.createdAt == withPubkey.createdAt) { "Amber substituted createdAt" }
                check(parsed.tags == withPubkey.tags) { "Amber substituted tags" }
                parsed
            } else {
                // Amber returned just the 128-char hex signature — reconstruct the event.
                check(result.length == 128) { "Amber returned unexpected result: $result" }
                Event(id, withPubkey.pubkey, withPubkey.createdAt, withPubkey.kind, withPubkey.tags, withPubkey.content, result)
            }
            check(signed.verify()) { "Amber returned an event with invalid signature" }
            signed
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> {
        val intent = buildIntent(plaintext, "nip44_encrypt").apply {
            putExtra("pubKey", recipientPubkey)
        }
        return bridge.request(intent)
    }

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> {
        val intent = buildIntent(ciphertext, "nip44_decrypt").apply {
            putExtra("pubKey", senderPubkey)
        }
        return bridge.request(intent)
    }

    private fun buildIntent(payload: String, type: String): Intent =
        Intent(Intent.ACTION_VIEW, "nostrsigner:$payload".toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            `package` = AMBER_PACKAGE
            putExtra("type", type)
            putExtra("current_user", pubkey)
            putExtra("package", callerPackage)
        }

    companion object {
        const val REQUEST_CODE = 9001
        const val AMBER_PACKAGE = "com.greenart7c3.nostrsigner"
    }
}

