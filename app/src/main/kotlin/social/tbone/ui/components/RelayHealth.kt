package social.tbone.ui.components

/** Coarse relay health buckets used by the declutter-mode status light. */
enum class RelayHealth {
    OFFLINE,
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * 0 relays is offline; below one-third is orange; from one-third through
 * three-quarters is yellow; more than three-quarters connected is green.
 */
fun relayHealth(connected: Int, total: Int): RelayHealth {
    if (total <= 0 || connected <= 0) return RelayHealth.OFFLINE
    val active = connected.coerceAtMost(total).toLong()
    val configured = total.toLong()
    return when {
        active * 4 > configured * 3 -> RelayHealth.HIGH
        active * 3 < configured -> RelayHealth.LOW
        else -> RelayHealth.MEDIUM
    }
}
