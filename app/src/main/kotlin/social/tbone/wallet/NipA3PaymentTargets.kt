package social.tbone.wallet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.UnsignedEvent

/** NIP-A3 payment-target helpers for public receive addresses (Monero + Lightning). */
object NipA3PaymentTargets {
    const val KIND = EventKind.PAYMENT_TARGETS
    private const val PAYTO = "payto"
    private const val MONERO = "monero"
    private const val LIGHTNING = "lightning"

    /** Whether the event advertises any Monero or read-alias target tag. */
    fun hasMoneroTag(event: Event?): Boolean = event?.takeIf { it.kind == KIND }
        ?.tags
        ?.any { tag ->
            tag.valueAt(0).equals(PAYTO, ignoreCase = true) &&
                (tag.valueAt(1).equals(MONERO, ignoreCase = true) || tag.valueAt(1).equals("xmr", ignoreCase = true))
        } == true

    /** Reads the first usable Monero target, accepting the common `xmr` alias. */
    fun moneroAddress(event: Event?): String? {
        if (event?.kind != KIND) return null
        return event.tags.asSequence()
            .filter { it.valueAt(0).equals(PAYTO, ignoreCase = true) }
            .filter { it.valueAt(1).equals(MONERO, ignoreCase = true) || it.valueAt(1).equals("xmr", ignoreCase = true) }
            .mapNotNull { it.valueAt(2)?.let(MoneroAddressPolicy::normalizeOrNull) }
            .firstOrNull()
    }

    /** Whether the event advertises a Lightning target. */
    fun hasLightningTag(event: Event?): Boolean = event?.takeIf { it.kind == KIND }
        ?.tags
        ?.any { tag ->
            tag.valueAt(0).equals(PAYTO, ignoreCase = true) &&
                (tag.valueAt(1).equals(LIGHTNING, ignoreCase = true) || tag.valueAt(1).equals("lud16", ignoreCase = true) || tag.valueAt(1).equals("lnurl", ignoreCase = true))
        } == true

    /** Reads the first usable Lightning address (lud16 / lnurl). */
    fun lightningAddress(event: Event?): String? {
        if (event?.kind != KIND) return null
        return event.tags.asSequence()
            .filter { it.valueAt(0).equals(PAYTO, ignoreCase = true) }
            .filter { it.valueAt(1).equals(LIGHTNING, ignoreCase = true) || it.valueAt(1).equals("lud16", ignoreCase = true) || it.valueAt(1).equals("lnurl", ignoreCase = true) || it.valueAt(1).equals("bitcoin", ignoreCase = true) }
            .mapNotNull { it.valueAt(2)?.let(LightningAddressPolicy::normalizeOrNull) }
            .firstOrNull()
    }

    /**
     * Creates the next replaceable kind-10133 event while changing only the
     * author's Monero/XMR payto tags. Other payment targets and unknown tags are
     * preserved byte-for-byte. A null address removes only Monero targets.
     */
    fun updateMoneroAddress(
        pubkey: String,
        existing: Event?,
        address: String?,
        createdAt: Long,
    ): UnsignedEvent {
        require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
        require(existing == null || (existing.kind == KIND && existing.pubkey.equals(pubkey, ignoreCase = true))) {
            "payment-target event does not belong to this account"
        }
        val normalizedAddress = address?.takeIf { it.isNotBlank() }?.let(MoneroAddressPolicy::normalize)
        val preservedTags = existing?.tags.orEmpty().filterNot { tag ->
            tag.valueAt(0).equals(PAYTO, ignoreCase = true) &&
                (tag.valueAt(1).equals(MONERO, ignoreCase = true) || tag.valueAt(1).equals("xmr", ignoreCase = true))
        }
        val tags = buildList {
            addAll(preservedTags)
            normalizedAddress?.let { value ->
                add(buildJsonArray {
                    add(PAYTO)
                    add(MONERO)
                    add(value)
                })
            }
        }
        return UnsignedEvent(
            pubkey = pubkey.lowercase(),
            createdAt = createdAt,
            kind = KIND,
            tags = tags,
            content = existing?.content.orEmpty(),
        )
    }

    /**
     * Creates the next replaceable kind-10133 event while changing only the
     * author's Lightning payto tags. Mirrors [updateMoneroAddress] exactly.
     */
    fun updateLightningAddress(
        pubkey: String,
        existing: Event?,
        address: String?,
        createdAt: Long,
    ): UnsignedEvent {
        require(pubkey.matches(Regex("^[0-9a-fA-F]{64}$"))) { "invalid Nostr public key" }
        require(existing == null || (existing.kind == KIND && existing.pubkey.equals(pubkey, ignoreCase = true))) {
            "payment-target event does not belong to this account"
        }
        val normalizedAddress = address?.takeIf { it.isNotBlank() }?.let(LightningAddressPolicy::normalize)
        val preservedTags = existing?.tags.orEmpty().filterNot { tag ->
            tag.valueAt(0).equals(PAYTO, ignoreCase = true) &&
                (tag.valueAt(1).equals(LIGHTNING, ignoreCase = true) || tag.valueAt(1).equals("lud16", ignoreCase = true) || tag.valueAt(1).equals("lnurl", ignoreCase = true) || tag.valueAt(1).equals("bitcoin", ignoreCase = true))
        }
        val tags = buildList {
            addAll(preservedTags)
            normalizedAddress?.let { value ->
                add(buildJsonArray {
                    add(PAYTO)
                    add(LIGHTNING)
                    add(value)
                })
            }
        }
        return UnsignedEvent(
            pubkey = pubkey.lowercase(),
            createdAt = createdAt,
            kind = KIND,
            tags = tags,
            content = existing?.content.orEmpty(),
        )
    }

    private fun JsonArray.valueAt(index: Int): String? =
        (getOrNull(index) as? JsonPrimitive)?.contentOrNull
}

/**
 * Shape-only Monero address validation for a wallet hand-off. The app never
 * handles private wallet keys or attempts to validate a Monero checksum; it
 * accepts standard and integrated address lengths and the Monero base58 alphabet.
 */
object MoneroAddressPolicy {
    private const val MONERO_BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private const val MIN_LENGTH = 90
    private const val MAX_LENGTH = 120

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val value = if (trimmed.startsWith("monero:", ignoreCase = true)) {
            trimmed.substringAfter(':').trim()
        } else {
            trimmed
        }
        require(value.length in MIN_LENGTH..MAX_LENGTH) {
            "Monero address must be a standard or integrated address"
        }
        require(value.all { it in MONERO_BASE58 }) { "Monero address contains invalid characters" }
        return value
    }

    fun normalizeOrNull(raw: String): String? = runCatching { normalize(raw) }.getOrNull()
}

/**
 * Minimal Lightning address validation (lud16 / lnurl). Mirrors Monero's
 * shape-only check — no network lookup, just format.
 */
object LightningAddressPolicy {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Lightning address is empty" }
        val value = when {
            trimmed.startsWith("lightning:", ignoreCase = true) -> trimmed.substringAfter(':').trim()
            trimmed.startsWith("lnurl", ignoreCase = true) -> trimmed
            else -> trimmed
        }
        // lud16: user@domain
        if (value.contains("@")) {
            require(value.count { it == '@' } == 1) { "Invalid Lightning address" }
            val (user, domain) = value.split("@")
            require(user.isNotEmpty() && domain.isNotEmpty() && domain.contains(".") && !user.contains(" ") && !domain.contains(" ")) {
                "Invalid Lightning address"
            }
            require(domain.length in 3..253) { "Invalid Lightning domain" }
            return value.lowercase()
        }
        // lnurl bech32
        if (value.lowercase().startsWith("lnurl1")) {
            require(value.length in 10..2000) { "Invalid lnurl length" }
            return value.lowercase()
        }
        // Also allow bare lightning address without @ check already done
        error("Lightning address must be lud16 (user@domain) or lnurl1...")
    }

    fun normalizeOrNull(raw: String): String? = runCatching { normalize(raw) }.getOrNull()
}

/** URIs mirrored from the NIP-A3 hand-off pattern used by Amethyst. */
object WalletPaymentUris {
    fun lightningInvoice(invoice: String): String {
        val value = invoice.trim()
        require(value.isNotEmpty()) { "Lightning invoice is empty" }
        return "lightning:$value"
    }

    fun moneroAddress(address: String): String = "monero:${MoneroAddressPolicy.normalize(address)}"
}
