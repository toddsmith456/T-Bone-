package social.tbone.wallet

/**
 * Reconnect is a desired-connection policy, not a screen-lifecycle policy.
 * A saved wallet is retried unless the user explicitly disconnected it.
 */
object NwcReconnectPolicy {
    fun shouldAutoReconnect(
        hasSavedConnection: Boolean,
        explicitlyDisconnected: Boolean,
        state: NwcConnectionState,
    ): Boolean =
        hasSavedConnection && !explicitlyDisconnected && state != NwcConnectionState.READY &&
            state != NwcConnectionState.DISCONNECTED
}
