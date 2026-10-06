package social.tbone.wallet

import java.net.URLDecoder

/** Parsed, validated parts of a Nostr Wallet Connect URI. */
data class ParsedNwcConnection(
    val walletPubkey: String,
    val relayUrls: List<String>,
    val clientSecret: ByteArray,
    val lud16: String?,
)

/**
 * Strict NIP-47 connection parser. It accepts both the normal URL-encoded
 * relay parameter and the common pasted form with `relay=wss://...`.
 */
object NwcProtocol {
    private val HEX_64 = Regex("^[0-9a-fA-F]{64}$")
    private val VALID_RELAY = Regex("^wss?://[^/?#]+(?:/[^?#]*)?$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): Result<ParsedNwcConnection> = runCatching {
        val value = raw.trim()
        require(value.length <= 8_192) { "NWC address is too long" }

        val schemeEnd = value.indexOf("://")
        require(schemeEnd > 0) { "NWC address is missing its scheme" }
        val scheme = value.substring(0, schemeEnd)
        require(
            scheme.equals("nostr+walletconnect", ignoreCase = true) ||
                scheme.equals("nwc", ignoreCase = true),
        ) {
            "NWC address must start with nostr+walletconnect://"
        }

        val afterScheme = value.substring(schemeEnd + 3)
        val queryStart = afterScheme.indexOf('?')
        require(queryStart > 0) { "NWC address is missing query parameters" }
        val authority = afterScheme.substring(0, queryStart)
        val query = afterScheme.substring(queryStart + 1)
        require(authority.isNotBlank() && !authority.contains('@') && !query.contains('#')) {
            "NWC address has an invalid authority"
        }
        val walletPubkey = authority.substringBefore(':').trim().lowercase()
        require(HEX_64.matches(walletPubkey)) { "invalid wallet service public key" }

        val params = query.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2) return@mapNotNull null
            val key = decode(pieces[0]).trim()
            val valuePart = decode(pieces[1]).trim()
            key to valuePart
        }

        val relays = params.filter { it.first == "relay" }
            .map { it.second }
            .filter { it.startsWith("wss://", true) || it.startsWith("ws://", true) }
            .map { it.trimEnd('/') }
            .distinct()
            .take(5)
        require(relays.isNotEmpty()) { "NWC address needs at least one ws(s) relay" }
        relays.forEach { require(VALID_RELAY.matches(it)) { "invalid NWC relay URL" } }

        val secretHex = params.firstOrNull { it.first == "secret" }?.second
            ?.lowercase()
            ?: error("NWC address is missing its secret")
        require(HEX_64.matches(secretHex)) { "invalid NWC secret" }

        val lud16 = params.firstOrNull { it.first == "lud16" }
            ?.second
            ?.takeIf { it.length <= 320 && it.contains('@') }

        ParsedNwcConnection(
            walletPubkey = walletPubkey,
            relayUrls = relays,
            clientSecret = secretHex.hexToBytes(),
            lud16 = lud16,
        )
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
        ((Character.digit(this[index * 2], 16) shl 4) +
            Character.digit(this[index * 2 + 1], 16)).toByte()
    }
}
