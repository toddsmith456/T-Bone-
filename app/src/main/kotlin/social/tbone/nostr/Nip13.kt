package social.tbone.nostr

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/**
 * NIP-13: Proof of Work generation and verification for Nostr events.
 *
 * Events commit to a target difficulty via `["nonce", "<counter>", "<target>"]`,
 * and their SHA-256 event ID must have at least `<target>` leading zero bits.
 */
object Nip13 {

    const val NONCE_TAG = "nonce"

    /**
     * Counts leading zero bits in a 64-character hex Nostr event ID.
     * Returns 0 if the ID is malformed.
     */
    fun leadingZeroBits(hexId: String): Int {
        if (hexId.length != 64) return 0
        var bits = 0
        for (i in 0 until 64) {
            val nibble = Character.digit(hexId[i], 16)
            if (nibble < 0) return 0
            if (nibble == 0) {
                bits += 4
            } else {
                bits += when {
                    nibble >= 8 -> 0
                    nibble >= 4 -> 1
                    nibble >= 2 -> 2
                    else -> 3
                }
                return bits
            }
        }
        return bits
    }

    /** Counts leading zero bits directly on a 32-byte SHA-256 digest. */
    internal fun leadingZeroBits(hashBytes: ByteArray): Int {
        var bits = 0
        for (b in hashBytes) {
            val v = b.toInt() and 0xFF
            if (v == 0) {
                bits += 8
            } else {
                bits += Integer.numberOfLeadingZeros(v) - 24
                return bits
            }
        }
        return bits
    }

    /**
     * Extracts the committed target difficulty from a NIP-13 `nonce` tag
     * (`["nonce", "<value>", "<target>"]`), or null if absent/malformed.
     */
    fun committedTargetDifficulty(event: Event): Int? =
        committedTargetDifficulty(event.parsedTags)

    fun committedTargetDifficulty(tags: List<Tag>): Int? {
        val nonceTag = tags.firstOrNull { it.name == NONCE_TAG } ?: return null
        val value = nonceTag.value(1)
        if (value.isNullOrEmpty()) return null
        val target = nonceTag.value(2)?.toIntOrNull() ?: return null
        return target.takeIf { it >= 0 }
    }

    /**
     * Returns true iff [event] has BOTH a committed NIP-13 target difficulty
     * of at least [minBits] AND an event ID with at least [minBits] leading
     * zero bits (preventing bulk spammers targeting lower difficulty from
     * passing on lucky hashes).
     */
    fun hasValidPow(event: Event, minBits: Int): Boolean {
        if (minBits <= 0) return true
        val committed = committedTargetDifficulty(event) ?: return false
        if (committed < minBits) return false
        return leadingZeroBits(event.id) >= minBits
    }

    /**
     * Mines a NIP-13 `nonce` tag onto [unsigned] so that `result.computeId()`
     * has at least [targetBits] leading zero bits.
     *
     * Any existing `nonce` tag is replaced. Pre-serializes the static NIP-01
     * prefix/suffix around the integer counter so each candidate hash avoids
     * JSON AST allocations (~10x faster on mobile CPUs; 16 bits typically
     * completes in 25–120 ms).
     */
    suspend fun mine(
        unsigned: UnsignedEvent,
        targetBits: Int,
        startNonce: Long = 0L,
    ): UnsignedEvent {
        require(targetBits in 0..64) { "targetBits out of range: $targetBits" }
        val baseTags = unsigned.tags.filterNot { isNonceTag(it) }
        if (targetBits == 0) {
            val tag = nonceTag("0", "0")
            return unsigned.copy(tags = baseTags + listOf(tag))
        }

        val targetStr = targetBits.toString()
        // Build prefix & suffix bytes matching Event.serializeForId byte-for-byte.
        val sentinel = "__NIP13_NONCE_SENTINEL__"
        val template = unsigned.copy(tags = baseTags + listOf(nonceTag(sentinel, targetStr)))
        val fullTemplate = String(
            Event.serializeForId(
                template.pubkey,
                template.createdAt,
                template.kind,
                template.tags,
                template.content,
            ),
            Charsets.UTF_8,
        )
        val splitIndex = fullTemplate.indexOf(sentinel)
        check(splitIndex >= 0) { "NIP-13 template serialization failed" }
        val prefixBytes = fullTemplate.substring(0, splitIndex).toByteArray(Charsets.UTF_8)
        val suffixBytes = fullTemplate.substring(splitIndex + sentinel.length).toByteArray(Charsets.UTF_8)

        val digest = MessageDigest.getInstance("SHA-256")
        var nonce = startNonce
        var iterations = 0
        while (true) {
            if ((iterations and 0x0FFF) == 0) {
                currentCoroutineContext().ensureActive()
            }
            val nonceStr = nonce.toString()
            digest.reset()
            digest.update(prefixBytes)
            digest.update(nonceStr.toByteArray(Charsets.US_ASCII))
            digest.update(suffixBytes)
            val hash = digest.digest()
            if (leadingZeroBits(hash) >= targetBits) {
                val mined = unsigned.copy(tags = baseTags + listOf(nonceTag(nonceStr, targetStr)))
                val computedId = mined.computeId()
                check(leadingZeroBits(computedId) >= targetBits) {
                    "Mined NIP-13 id verification mismatch"
                }
                return mined
            }
            nonce++
            iterations++
        }
    }

    private fun nonceTag(nonce: String, target: String): JsonArray = buildJsonArray {
        add(JsonPrimitive(NONCE_TAG))
        add(JsonPrimitive(nonce))
        add(JsonPrimitive(target))
    }

    private fun isNonceTag(tag: JsonArray): Boolean =
        (tag.getOrNull(0) as? JsonPrimitive)?.contentOrNull == NONCE_TAG
}
