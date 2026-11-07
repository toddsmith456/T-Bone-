package social.tbone.security

import kotlinx.coroutines.CancellationException

/** Runs all independent cleanup steps and records failures instead of hiding them. */
internal class WipeSteps(private val onFailure: (String, Throwable) -> Unit) {
    var failed: Boolean = false
        private set

    suspend fun run(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            onFailure(label, e)
        }
    }
}
