package social.tbone.nostr

import android.util.LruCache
import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import social.tbone.Tunables
import timber.log.Timber
import java.security.MessageDigest

/** Caches verified event IDs so Schnorr verification is only done once per unique event. */
object VerifiedEventCache {
    // LruCache is internally synchronized and bounded, preventing unbounded growth.
    private val cache = LruCache<String, Unit>(Tunables.VERIFIED_EVENT_CACHE_SIZE)
    fun contains(id: String): Boolean = cache.get(id) != null
    fun add(id: String) { cache.put(id, Unit) }
}

/**
 * A Nostr event as defined in NIP-01.
 *
 * All fields are immutable. Events signed by an external signer (Amber, nsecBunker)
 * arrive fully formed. Local draft events (pre-signing) use [UnsignedEvent].
 */
@Immutable
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<JsonArray>,
    val content: String,
    val sig: String,
) {
    /** Parsed tag wrappers for ergonomic access. */
    val parsedTags: List<Tag> by lazy { tags.map { Tag.fromJsonArray(it) } }

    /**
     * Per-instance verification result. Because every field of [Event] is
     * immutable, once *this exact instance* has passed `verifyId() && sig`,
     * repeat calls across repositories/ViewModels can skip re-hashing safely
     * while a different (tampered) Event claiming the same id still hashes first.
     */
    @Transient
    @Volatile
    private var verifiedInstance: Boolean? = null

    /**
     * Verifies the event id and signature.
     * Returns false if either check fails — never throws.
     */
    fun verify(): Boolean {
        verifiedInstance?.let { return it }
        // A cached signature only authenticates the original payload, not a
        // different event claiming the same id. Always recompute the id first
        // for any unverified Event instance.
        val outcome = runCatching { verifyId() && (VerifiedEventCache.contains(id) || verifySignature()) }
        outcome.exceptionOrNull()?.let { error ->
            Timber.e(error, "Event verification threw for id=$id")
        }
        val result = outcome.getOrDefault(false)
        if (result) VerifiedEventCache.add(id)
        verifiedInstance = result
        return result
    }

    private fun verifyId(): Boolean {
        val serialized = serializeForId(pubkey, createdAt, kind, tags, content)
        val hash = sha256(serialized)
        return hash == id
    }

    private fun verifySignature(): Boolean {
        return Crypto.verifySchnorr(
            message = id.hexToBytes(),
            signature = sig.hexToBytes(),
            pubkey = pubkey.hexToBytes(),
        )
    }

    companion object {
        private val sha256Local = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

        fun fromJson(raw: String): Event = NostrJson.decodeFromString(raw)

        /**
         * Computes the canonical serialization used for the event id.
         * `[0, pubkey, created_at, kind, tags, content]`
         *
         * Writes directly into a StringBuilder to avoid intermediate JsonArray /
         * JsonElement allocations while matching `kotlinx.serialization` output
         * byte-for-byte.
         */
        fun serializeForId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<JsonArray>,
            content: String,
        ): ByteArray {
            val estimated = 128 + content.length + tags.size * 48
            val sb = StringBuilder(estimated)
            sb.append("[0,")
            appendJsonString(sb, pubkey)
            sb.append(',')
            sb.append(createdAt)
            sb.append(',')
            sb.append(kind)
            sb.append(",[")
            for (i in tags.indices) {
                if (i > 0) sb.append(',')
                sb.append(tags[i].toString())
            }
            sb.append("],")
            appendJsonString(sb, content)
            sb.append(']')
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun appendJsonString(sb: StringBuilder, value: String) {
            // Fast path: scan and write directly unless an unusual control char (< 0x20
            // outside standard JSON short escapes) needs exact kotlinx.serialization handling.
            for (i in 0 until value.length) {
                val c = value[i]
                if (c < ' ' && c != '\n' && c != '\r' && c != '\t' && c != '\b' && c != '\u000C') {
                    sb.append(JsonPrimitive(value).toString())
                    return
                }
            }
            sb.append('"')
            for (i in 0 until value.length) {
                when (val c = value[i]) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> sb.append(c)
                }
            }
            sb.append('"')
        }

        fun sha256(input: ByteArray): String {
            val digest = sha256Local.get()!!.apply { reset() }
            return digest.digest(input).toHex()
        }
    }
}

/**
 * A Nostr event that has not yet been signed.
 * Passed to a [social.tbone.signer.NostrSigner] to produce a signed [Event].
 */
@Immutable
@Serializable
data class UnsignedEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis() / 1000,
    val kind: Int,
    val tags: List<JsonArray> = emptyList(),
    val content: String,
) {
    fun computeId(): String {
        val serialized = Event.serializeForId(pubkey, createdAt, kind, tags, content)
        return Event.sha256(serialized)
    }
}

// ── Hex utilities ─────────────────────────────────────────────────────────────

private val HEX_CHARS = "0123456789abcdef".toCharArray()

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    var j = 0
    for (b in this) {
        val v = b.toInt() and 0xFF
        out[j++] = HEX_CHARS[v ushr 4]
        out[j++] = HEX_CHARS[v and 0x0F]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length, got $length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi in 0..15 && lo in 0..15) {
            "Non-hex characters at index ${i * 2}: '${this[i * 2]}${this[i * 2 + 1]}'"
        }
        ((hi shl 4) + lo).toByte()
    }
}
