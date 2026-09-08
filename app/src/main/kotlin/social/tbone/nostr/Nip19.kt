package social.tbone.nostr

/**
 * NIP-19: bech32-encoded entities.
 * Handles npub ↔ hex conversion for public keys.
 * Encoding for note, nprofile, nevent etc. can be added as needed.
 */
object Nip19 {

    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    // ── Public API ────────────────────────────────────────────────────────────

    /** Converts an npub1… string to a 64-char lowercase hex pubkey, or null if invalid. */
    fun npubToHex(npub: String): String? = runCatching {
        val (hrp, bytes) = decode(npub) ?: return null
        if (hrp != "npub" || bytes.size != 32) return null
        bytes.toHex()
    }.getOrNull()

    /** Converts a 64-char hex pubkey to an npub1… string. */
    fun hexToNpub(hex: String): String =
        encode("npub", hex.hexToBytes()) ?: error("bech32 encoding failed for npub: $hex")

    /** Converts a 64-char hex event ID to a note1… string. */
    fun hexToNote(hex: String): String =
        encode("note", hex.hexToBytes()) ?: error("bech32 encoding failed for note: $hex")

    /** Returns true if the string looks like a bech32-encoded Nostr entity. */
    fun isBech32(s: String): Boolean =
        s.startsWith("npub1") || s.startsWith("note1") ||
        s.startsWith("nprofile1") || s.startsWith("nevent1")

    /**
     * Decodes a note1… or nostr:note1… string to a 64-char hex event ID.
     * note1 is simple bech32 with hrp="note" and 32-byte payload.
     */
    fun noteToHex(input: String): String? = runCatching {
        val bech32 = input.removePrefix("nostr:").lowercase()
        val (hrp, bytes) = decode(bech32) ?: return null
        if (hrp != "note" || bytes.size != 32) return null
        bytes.toHex()
    }.getOrNull()

    /**
     * Decodes a nevent1… or nostr:nevent1… string to a 64-char hex event ID.
     * nevent1 uses TLV encoding; type 0 (special) is the 32-byte event ID.
     */
    fun neventToHex(input: String): String? = runCatching {
        val bech32 = input.removePrefix("nostr:").lowercase()
        val (hrp, bytes) = decode(bech32) ?: return null
        if (hrp != "nevent") return null
        var i = 0
        while (i + 1 < bytes.size) {
            val type = bytes[i].toInt() and 0xFF
            val len  = bytes[i + 1].toInt() and 0xFF
            i += 2
            if (type == 0 && len == 32 && i + len <= bytes.size) {
                return bytes.sliceArray(i until i + len).toHex()
            }
            i += len
        }
        null
    }.getOrNull()

    /**
     * Decodes an nprofile1… string to the 64-char hex pubkey it encodes.
     * nprofile1 uses TLV encoding; type 0 (special) is the 32-byte pubkey.
     */
    fun nprofileToHex(input: String): String? = runCatching {
        val bech32 = input.removePrefix("nostr:").lowercase()
        val (hrp, bytes) = decode(bech32) ?: return null
        if (hrp != "nprofile") return null
        var i = 0
        while (i + 1 < bytes.size) {
            val type = bytes[i].toInt() and 0xFF
            val len  = bytes[i + 1].toInt() and 0xFF
            i += 2
            if (type == 0 && len == 32 && i + len <= bytes.size) {
                return bytes.sliceArray(i until i + len).toHex()
            }
            i += len
        }
        null
    }.getOrNull()

    /** Extracts a hex event ID from a nostr:note1… or nostr:nevent1… URI. */
    fun nostrUriToEventId(uri: String): String? = when {
        uri.contains("nevent1") -> neventToHex(uri)
        uri.contains("note1")   -> noteToHex(uri)
        else -> null
    }

    /**
     * Normalises a pubkey to hex regardless of whether it arrived as npub or hex.
     * Returns null if the input is not a valid pubkey in either format.
     */
    fun normalisePubkey(input: String): String? = when {
        input.startsWith("npub1") -> npubToHex(input)
        input.length == 64 && input.all { it.isHexChar() } -> input.lowercase()
        else -> null
    }

    // ── Bech32 core ───────────────────────────────────────────────────────────

    private fun decode(bech32: String): Pair<String, ByteArray>? {
        val lower = bech32.lowercase()
        val pos = lower.lastIndexOf('1')
        if (pos < 1 || pos + 7 > lower.length) return null

        val hrp = lower.substring(0, pos)
        val data = lower.substring(pos + 1).map { c ->
            val idx = CHARSET.indexOf(c)
            if (idx == -1) return null
            idx.toByte()
        }

        if (!verifyChecksum(hrp, data)) return null

        return hrp to (convertBits(data.dropLast(6), fromBits = 5, toBits = 8, pad = false) ?: return null)
    }

    private fun encode(hrp: String, data: ByteArray): String? {
        val data5 = convertBits(data.map { it }, fromBits = 8, toBits = 5, pad = true)?.toList() ?: return null
        val checksum = createChecksum(hrp, data5)
        return hrp + "1" + (data5 + checksum).joinToString("") { CHARSET[it.toInt() and 0xFF].toString() }
    }

    private fun polymod(values: List<Byte>): Int {
        var chk = 1
        for (v in values) {
            val top = chk ushr 25
            chk = ((chk and 0x1ffffff) shl 5) xor (v.toInt() and 0xFF)
            for (i in 0..4) {
                if ((top ushr i) and 1 != 0) chk = chk xor GENERATOR[i]
            }
        }
        return chk
    }

    private fun hrpExpand(hrp: String): List<Byte> =
        hrp.map { (it.code ushr 5).toByte() } +
        listOf(0.toByte()) +
        hrp.map { (it.code and 31).toByte() }

    private fun verifyChecksum(hrp: String, data: List<Byte>): Boolean =
        polymod(hrpExpand(hrp) + data) == 1

    private fun createChecksum(hrp: String, data: List<Byte>): List<Byte> {
        val values = hrpExpand(hrp) + data + List(6) { 0.toByte() }
        val poly = polymod(values) xor 1
        return (0..5).map { i -> ((poly ushr (5 * (5 - i))) and 31).toByte() }
    }

    private fun convertBits(data: List<Byte>, fromBits: Int, toBits: Int, pad: Boolean): ByteArray? {
        var acc = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        val maxv = (1 shl toBits) - 1
        for (value in data) {
            val v = value.toInt() and 0xFF
            if (v ushr fromBits != 0) return null
            acc = (acc shl fromBits) or v
            bits += fromBits
            while (bits >= toBits) {
                bits -= toBits
                out.add(((acc ushr bits) and maxv).toByte())
            }
        }
        if (pad) {
            if (bits > 0) out.add(((acc shl (toBits - bits)) and maxv).toByte())
        } else if (bits >= fromBits || ((acc shl (toBits - bits)) and maxv) != 0) {
            return null
        }
        return out.toByteArray()
    }

    private fun Char.isHexChar() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
