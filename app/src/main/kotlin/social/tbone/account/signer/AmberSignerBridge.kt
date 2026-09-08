package social.tbone.account.signer

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridge between the coroutine-based [AmberSigner] and Android's activity result API.
 *
 * [AmberSigner] posts a pending [AmberRequest] here and suspends.
 * [MainActivity] observes [pendingRequest], launches the intent, and calls
 * [onResult] when the activity result arrives.
 *
 * Only one request is in-flight at a time — Amber is an interactive signer.
 */
@Singleton
class AmberSignerBridge @Inject constructor(
    private val signingIndicator: SigningIndicator,
) {

    private val _pendingRequest = MutableStateFlow<AmberRequest?>(null)
    val pendingRequest: StateFlow<AmberRequest?> = _pendingRequest.asStateFlow()

    /** Called by [AmberSigner] to post a request and await the result. */
    suspend fun request(intent: Intent): Result<String> {
        val req = AmberRequest(intent)
        signingIndicator.start()
        _pendingRequest.value = req
        return try {
            req.await()
        } finally {
            _pendingRequest.value = null
            signingIndicator.stop()
        }
    }

    /** Called by MainActivity when the activity result arrives. */
    fun onResult(resultCode: Int, data: Intent?) {
        Timber.d("onResult: code=$resultCode extras=${data?.extras?.keySet()}")
        val req = _pendingRequest.value ?: run {
            Timber.w("onResult: no pending request")
            return
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            val result = data.getStringExtra("result") ?: data.getStringExtra("event")
            Timber.d("onResult OK: result=${result?.take(200)}")
            if (result != null) {
                req.complete(Result.success(result))
            } else {
                req.complete(Result.failure(IllegalStateException("Amber returned no result")))
            }
        } else {
            Timber.w("onResult: cancelled or error, code=$resultCode")
            req.complete(Result.failure(IllegalStateException("Amber: user cancelled or error (code $resultCode)")))
        }
    }
}
