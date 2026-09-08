package social.tbone.nostr.identity

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * BIP-39 phrase fingerprinting for Nostr pubkeys.
 *
 * Derivation:
 *   hash     = SHA-256(pubkey_bytes)   -- pubkey is 32 raw bytes (hex-decoded)
 *   bits 0–65 → 6 words × 11 bits → indices into 2048-word BIP-39 list
 *   bits 66–81 → 16-cell avatar dot grid (one bit per cell)
 *
 * The 3-word handle (bits 0–32) is a strict prefix of the 6-word fingerprint
 * (bits 0–65), so they never disagree.
 *
 * Wordlist asset: app/src/main/assets/bip39_english.txt (2048 lines, one word each).
 * Call [init] once at app startup before any [wordsFor] call.
 */
object Phrase {

    private val cache = ConcurrentHashMap<String, List<String>>()
    @Volatile private var wordlist: List<String>? = null

    /** Load the BIP-39 word list from assets. Call once in Application.onCreate(). */
    fun init(context: Context) {
        if (wordlist != null) return
        wordlist = runCatching {
            context.assets
                .open("bip39_english.txt")
                .bufferedReader()
                .readLines()
                .filter { it.isNotBlank() }
                .take(2048)
        }.getOrNull()
    }

    /**
     * Returns [length] BIP-39 words (1–6) deterministically derived from [pubkeyHex].
     * Cached per pubkey. Returns placeholder tokens if the wordlist was not loaded.
     */
    fun wordsFor(pubkeyHex: String, length: Int = 6): List<String> {
        require(length in 1..6) { "length must be 1–6" }
        val words = cache.getOrPut(pubkeyHex) { derive(pubkeyHex) }
        return words.take(length)
    }

    /**
     * Returns a 16-element boolean list for the 4×4 avatar dot grid.
     * Uses bits 66–81 of the same SHA-256 hash used for the phrase.
     */
    fun avatarGrid(pubkeyHex: String): List<Boolean> {
        val hash = sha256(pubkeyHex.hexToBytes())
        return (66 until 82).map { offset -> extractBit(hash, offset) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun derive(pubkeyHex: String): List<String> {
        val wl = wordlist ?: return List(6) { "·····" }
        val hash = sha256(pubkeyHex.hexToBytes())
        return (0 until 6).map { i ->
            val idx = extract11Bits(hash, i * 11)
            wl[idx % 2048]
        }
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun extractBit(data: ByteArray, offset: Int): Boolean {
        val byteIdx = offset / 8
        val bitIdx = 7 - (offset % 8)
        return byteIdx < data.size && (data[byteIdx].toInt() ushr bitIdx) and 1 == 1
    }

    private fun extract11Bits(data: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 0 until 11) {
            result = (result shl 1) or if (extractBit(data, offset + i)) 1 else 0
        }
        return result
    }

    private fun String.hexToBytes(): ByteArray {
        val s = this.lowercase().padEnd(64, '0').take(64)
        return ByteArray(32) { i ->
            ((Character.digit(s[2 * i], 16) shl 4) or Character.digit(s[2 * i + 1], 16)).toByte()
        }
    }
}
