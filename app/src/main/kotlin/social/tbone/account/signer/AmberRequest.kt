package social.tbone.account.signer

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred

/**
 * A single pending request to the Amber signer app.
 * Wraps a [CompletableDeferred] so the requester can suspend until
 * the activity result arrives via [AmberSignerBridge.onResult].
 */
class AmberRequest(val intent: Intent) {
    private val deferred = CompletableDeferred<Result<String>>()

    suspend fun await(): Result<String> = deferred.await()

    fun complete(result: Result<String>) {
        deferred.complete(result)
    }
}
