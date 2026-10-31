package social.tbone.nostr.relay

/** Owns only the dedicated Global pool; personal connections are never touched. */
class GlobalRelaySession(private val pool: RelayPool) {
    @Synchronized
    fun reconcile(active: Boolean, configured: Set<String>) {
        val wanted = if (active) configured.map(RelayPool::normalize)
            .filter(RelayPool::isValidRelayUrl).toSet() else emptySet()
        (pool.relayUrls() - wanted).forEach(pool::removeRelay)
        (wanted - pool.relayUrls()).forEach(pool::addRelay)
    }
}
