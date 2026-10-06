package social.tbone.wallet

/** Zap controls are visible only after capability validation completed successfully. */
fun canShowZapControls(zapsEnabled: Boolean, connectionState: NwcConnectionState): Boolean =
    zapsEnabled && connectionState == NwcConnectionState.READY
