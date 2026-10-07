package social.tbone.wallet

import java.math.BigInteger

/** Minimal, strict BOLT-11 amount reader used before an invoice is handed to a wallet. */
object LightningInvoice {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GENERATOR = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b)

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
