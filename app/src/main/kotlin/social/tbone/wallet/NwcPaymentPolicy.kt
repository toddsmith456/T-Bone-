package social.tbone.wallet

/** Payment outcomes used to keep an unknown Lightning result out of the retry path. */
enum class NwcPaymentState { SUCCEEDED, FAILED, UNKNOWN }

object NwcPaymentPolicy {
    fun classify(result: Result<Unit>): NwcPaymentState = when {
        result.isSuccess -> NwcPaymentState.SUCCEEDED
        result.exceptionOrNull() is NwcPaymentTimeoutException -> NwcPaymentState.UNKNOWN
        else -> NwcPaymentState.FAILED
    }

    /** A lost payment response is never safe to retry automatically. */
    fun mayRetry(state: NwcPaymentState): Boolean = state == NwcPaymentState.FAILED
}
