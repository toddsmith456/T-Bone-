package social.tbone.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * NIP-57 zap receipts (kind 9735).
 *
 * A receipt is published by the recipient's Lightning node, not by the sender,
 * so it cannot be verified as a signature from the sender — the receipt's own
 * pubkey is the LN node's. The zap request the sender signed travels inside the
 * receipt's `description` tag, which is where the sender's identity and the
 * comment live.
 */
object Nip57 {

    /** A zap receipt reduced to what a notification row needs. */
    data class Receipt(
        /** Pubkey of the account that was zapped (lowercase `p`). */
        val recipient: String,
        /** Pubkey of the account that paid (`P`), falling back to the request's author. */
        val sender: String?,
        /** Amount in millisatoshis, from the `amount` tag or the BOLT-11 invoice. */
        val amountMsats: Long?,
        /** The sender's own comment, when they wrote one. */
        val comment: String,
        /** Event id the zap was attached to, if any. */
        val zappedEventId: String?,
    ) {
        /** Sats, rounded down — the unit everything in the UI is expressed in. */
        val amountSats: Long? get() = amountMsats?.div(1000L)
    }

    /**
     * Reads [event] as a zap receipt, or returns null when it is not a usable
     * one. Never throws: a receipt with a malformed `description` still yields a
     * notification, just without the comment or the sender.
     */
    fun receipt(event: Event): Receipt? {
        if (event.kind != EventKind.ZAP_RECEIPT) return null
        val tags = event.parsedTags

        val recipient = tags.firstOrNull { it.name == "p" }?.value()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val request = tags.firstOrNull { it.name == "description" }?.value()
            ?.let(::parseRequest)

        val sender = tags.firstOrNull { it.name == "P" }?.value()
            ?.takeIf { it.isNotBlank() }
            ?: request?.pubkey

        // The `amount` tag is in msats. Some nodes omit it, so fall back to the
        // invoice, which encodes the amount for us.
        val amountMsats = tags.firstOrNull { it.name == "amount" }?.value()
            ?.toLongOrNull()
            ?: tags.firstOrNull { it.name == "bolt11" }?.value()
                ?.let { LightningAmount.fromBolt11(it) }

        return Receipt(
            recipient = recipient,
            sender = sender,
            amountMsats = amountMsats,
            comment = request?.content.orEmpty(),
            zappedEventId = tags.firstOrNull { it.name == "e" }?.value()
                ?.takeIf { it.isNotBlank() },
        )
    }

    /** The signed zap request carried in `description`. */
    private fun parseRequest(raw: String): Event? =
        runCatching {
            Json.parseToJsonElement(raw).jsonObject
        }.mapCatching { obj ->
            // Reuse the normal Event decoder so tag/field handling stays in one place.
            Json.decodeFromJsonElement(Event.serializer(), obj)
        }.getOrNull()

    /** The raw `description` tag (the embedded zap request) for consistency checks. */
    fun rawRequest(event: Event): String? =
        event.parsedTags.firstOrNull { it.name == "description" }?.value()

    /**
     * True when the receipt's `description` zap request really is about
     * [recipient] — i.e. the receipt is not just claiming an unrelated zap.
     * Malformed receipts return false so they are not surfaced.
     */
    fun isConsistent(receipt: Receipt, requestRaw: String?): Boolean {
        if (requestRaw == null) return true
        val request = parseRequest(requestRaw) ?: return false
        val requestRecipient = request.parsedTags.firstOrNull { it.name == "p" }?.value()
        return requestRecipient == null || requestRecipient.equals(receipt.recipient, ignoreCase = true)
    }
}

/**
 * Amount decoding for BOLT-11 invoices.
 *
 * Only the human-readable prefix is parsed (`lnbc250u1...` = 250 µBTC = 25,000
 * sats). Full invoice validation is not needed to display an amount, and the
 * receipt has already been accepted by the relay.
 */
internal object LightningAmount {

    private val PREFIX = Regex("^ln[a-z]+?([0-9]+)([munp]?)1", RegexOption.IGNORE_CASE)

    /** Amount in millisatoshis, or null when the invoice is amountless/unparseable. */
    fun fromBolt11(invoice: String): Long? {
        val match = PREFIX.find(invoice) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val msats = when (match.groupValues[2].lowercase()) {
            "" -> value * 100_000_000_000L // whole BTC
            "m" -> value * 100_000_000L    // milli-BTC
            "u" -> value * 100_000L        // micro-BTC
            "n" -> value * 100L            // nano-BTC
            "p" -> value / 10L             // pico-BTC
            else -> return null
        }
        return msats.takeIf { it > 0 }
    }
}
