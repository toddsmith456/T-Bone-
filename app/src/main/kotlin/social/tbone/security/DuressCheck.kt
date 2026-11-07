package social.tbone.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Once PIN submission begins, a verified duress match must initiate its wipe
 * even if the lock screen is disposed while the hash is being checked.
 * The wipe itself uses the application scope and its own durable marker.
 */
internal suspend fun verifyAndStartDuressWipe(
    verify: suspend () -> Boolean,
    startWipe: () -> Unit,
): Boolean = withContext(Dispatchers.Default + NonCancellable) {
    val matches = verify()
    if (matches) startWipe()
    matches
}
