package social.tbone.wallet

import java.math.BigInteger
import app.cash.lninvoice.PaymentRequest
import app.cash.lninvoice.Network
import app.cash.lninvoice.FieldTags
import java.security.MessageDigest
import java.time.Instant

/** Minimal, strict BOLT-11 amount reader used before an invoice is handed to a wallet. */
object LightningInvoice {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    /**
     * Returns the invoice's amount in millisatoshis, or null for an invalid or
     * amountless invoice. A zap must use an amount-bearing invoice because the
     * LNURL callback requested an exact amount.
     */
    fun amountMsats(invoice: String): Long? = runCatching {
        val value = invoice.trim()
        if (value.isEmpty() || value != value.lowercase() && value != value.uppercase()) return null
        val lower = value.lowercase()
        val separator = lower.lastIndexOf('1')
        if (separator < 1 || separator + 7 > lower.length) return null

        val hrp = lower.substring(0, separator)
        val data = lower.substring(separator + 1).map { character ->
            val index = CHARSET.indexOf(character)
            if (index < 0) return null
            index
        }
        if (polymod(hrpExpand(hrp) + data) != 1) return null

        val amount = when {
            hrp.startsWith("lnbcrt") -> hrp.removePrefix("lnbcrt")
            hrp.startsWith("lnbc") -> hrp.removePrefix("lnbc")
            hrp.startsWith("lntb") -> hrp.removePrefix("lntb")
            hrp.startsWith("lnsb") -> hrp.removePrefix("lnsb")
            hrp.startsWith("lnsim") -> hrp.removePrefix("lnsim")
            else -> return null
        }
        if (amount.isEmpty()) return null

        val unit = amount.last()
        val hasUnit = unit == 'm' || unit == 'n' || unit == 'p' || unit == 'u'
        val digits = if (hasUnit) amount.dropLast(1) else amount
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        val number = digits.toBigIntegerOrNull() ?: return null
        val millisatoshis = when (unit) {
            'm' -> number.multiply(BigInteger("100000000"))
            'u' -> number.multiply(BigInteger("100000"))
            'n' -> number.multiply(BigInteger("100"))
            'p' -> {
                if (number.mod(BigInteger.TEN) != BigInteger.ZERO) return null
                number.divide(BigInteger.TEN)
            }
            else -> number.multiply(BigInteger("100000000000"))
        }
        if (millisatoshis <= BigInteger.ZERO || millisatoshis > BigInteger.valueOf(Long.MAX_VALUE)) return null
        millisatoshis.toLong()
    }.getOrNull()

    /** Validate a NIP-57 invoice before exposing it to a payment wallet.
     * The wallet remains responsible for payment confirmation and settlement.
     */
    fun validateZap(invoice: String, expectedMsat: Long, signedZapJson: String, now: Instant = Instant.now()) {
        val amount = amountMsats(invoice) ?: throw IllegalArgumentException("Invalid Lightning invoice checksum or amount")
        require(amount == expectedMsat) { "Lightning invoice amount does not match the requested amount" }
        val parsed = try {
            PaymentRequest.parseUnsafe(invoice.trim())
        } catch (failure: Throwable) {
            // Parser errors can embed the full invoice. Do not expose or log it.
            throw IllegalArgumentException("The Lightning provider returned a malformed invoice")
        }
        require(parsed.network == Network.MAIN) { "The Lightning invoice is not for Bitcoin mainnet" }
        require(parsed.amount.fold({ false }, { it.millisat() == expectedMsat })) { "Lightning invoice amount is missing or incorrect" }
        require(!parsed.timestamp.isAfter(now.plusSeconds(300))) { "Lightning invoice timestamp is in the future" }
        require(parsed.timestamp.plus(parsed.expiry).isAfter(now)) { "The Lightning invoice has expired" }
        val fields = parsed.taggedFields
        val paymentHashes = fields.filter { it.tag == FieldTags.PAYMENT_HASH.tag }
        require(paymentHashes.size == 1 && paymentHashes.single().size == 52) { "Invoice has an invalid payment hash" }
        val descriptionHashes = fields.filter { it.tag == FieldTags.DESCRIPTION_HASH.tag }
        require(descriptionHashes.size == 1 && descriptionHashes.single().size == 52 &&
            fields.none { it.tag == FieldTags.DESCRIPTION.tag }) { "Provider invoice is not bound to a zap request" }
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(signedZapJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(parsed.descriptionHash.fold({ false }, { it.equals(expectedHash, ignoreCase = true) })) {
            "Provider invoice does not match this zap request"
        }
        val recovered = try { parsed.payeeNodePublicKey.toByteArray() }
        catch (failure: Throwable) { throw IllegalArgumentException("Lightning invoice signature is invalid") }
        val payeeFields = fields.filter { it.tag == FieldTags.PAYEE_NODE.tag }
        require(payeeFields.size <= 1) { "Invoice has duplicate payee keys" }
        if (payeeFields.isNotEmpty()) {
            val field = payeeFields.single()
            require(field.size == 53) { "Invoice has an invalid payee key" }
            val declared = convertWords(field.data.toByteArray(), 33)
            require(declared.contentEquals(recovered)) { "Invoice signature does not match its payee key" }
        }
    }

    private fun convertWords(words: ByteArray, expectedBytes: Int): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (word in words) {
            require(word.toInt() in 0..31) { "Invalid invoice data" }
            acc = ((acc shl 5) or word.toInt()) and 0x1fff
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 255).toByte())
            }
        }
        require(out.size == expectedBytes && (acc and ((1 shl bits) - 1)) == 0) { "Invalid invoice padding" }
        return out.toByteArray()
    }

    private fun polymod(values: List<Int>): Int {
        var checksum = 1
        for (value in values) {
            val top = checksum ushr 25
            checksum = ((checksum and 0x1ffffff) shl 5) xor value
            for (index in GENERATOR.indices) {
                if (((top ushr index) and 1) != 0) checksum = checksum xor GENERATOR[index]
            }
        }
        return checksum
    }

    private fun hrpExpand(hrp: String): List<Int> =
        hrp.map { it.code ushr 5 } + listOf(0) + hrp.map { it.code and 31 }
}
