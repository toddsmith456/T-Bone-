package social.tbone.wallet

/** Small, protocol-only helpers shared by the NWC handshake and unit tests. */
object NwcCapabilities {
    const val PAY_INVOICE = "pay_invoice"
    const val NIP44_V2 = "nip44_v2"
    /** NWC-06 per-payment metadata extension. */
    const val METADATA_EXTENSION = "06"

    /** NIP-47 info content is a space-separated list of methods. */
    fun methodsFromInfoContent(content: String): List<String> =
        content.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun supportsMethod(methods: Iterable<String>, method: String): Boolean =
        methods.any { it.equals(method, ignoreCase = true) }

    fun supportsNip44(schemes: Iterable<String>): Boolean =
        schemes.any { it.trim().equals(NIP44_V2, ignoreCase = true) }
}
