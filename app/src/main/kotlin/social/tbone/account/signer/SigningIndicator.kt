package social.tbone.account.signer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks when a signing request is in flight, across all signer backends.
 *
 * - Amber (NIP-55): the app switches to the external signer activity; this is
 *   set the moment the intent is posted so the UI shows a deliberate
 *   "signing…" state instead of an unexplained flash, and stays set until the
 *   result returns.
 * - nsecBunker (NIP-46): set while the request round-trips over the relay.
 * - Local Keystore: never set (instant, in-process).
 *
 * The [SigningOverlay] composable observes this and shows a subtle indicator
 * while any signer is working.
 */
@Singleton
class SigningIndicator @Inject constructor() {

    private val _signingInProgress = MutableStateFlow(false)
    val signingInProgress: StateFlow<Boolean> = _signingInProgress.asStateFlow()

    fun start() {
        _signingInProgress.value = true
    }

    fun stop() {
        _signingInProgress.value = false
    }
}
