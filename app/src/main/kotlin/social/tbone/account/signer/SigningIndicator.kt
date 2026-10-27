package social.tbone.account.signer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks when signing requests are in flight, across all signer backends.
 *
 * - Amber (NIP-55): the app switches to the external signer activity; this is
 *   set the moment the intent is posted so the UI shows a deliberate
 *   "signing…" state instead of an unexplained flash, and stays set until the
 *   result returns.
 * - nsecBunker (NIP-46): set while the request round-trips over the relay.
 * - Local Keystore: never set (instant, in-process).
 *
 * The [social.tbone.ui.SigningOverlay] composable observes this and shows a
 * subtle indicator while any signer is working. Requests are counted rather
 * than stored as a single boolean: a background relay AUTH request finishing
 * must not clear the overlay while the user's note is still being signed (or
 * leave it stuck when the requests finish in the opposite order).
 */
@Singleton
class SigningIndicator @Inject constructor() {

    private val lock = Any()
    private var activeRequests = 0
    private val _signingInProgress = MutableStateFlow(false)
    val signingInProgress: StateFlow<Boolean> = _signingInProgress.asStateFlow()

    fun start() {
        synchronized(lock) {
            activeRequests++
            _signingInProgress.value = true
        }
    }

    fun stop() {
        synchronized(lock) {
            activeRequests = (activeRequests - 1).coerceAtLeast(0)
            _signingInProgress.value = activeRequests > 0
        }
    }

    /** Used by tests and recovery paths that need a clean slate. */
    fun reset() {
        synchronized(lock) {
            activeRequests = 0
            _signingInProgress.value = false
        }
    }
}
