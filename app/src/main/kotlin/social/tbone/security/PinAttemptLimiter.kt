package social.tbone.security

import android.content.Context

/** Persistent online retry throttling. Duress must always be checked before this gate. */
internal class PinAttemptLimiter(context: Context, private val prefix: String) {
    private val prefs = context.getSharedPreferences("pin_retry_limits", Context.MODE_PRIVATE)

    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        (prefs.getLong("${prefix}_until", 0L) - now).coerceIn(0L, MAX_DELAY_MS)

    fun failure(now: Long = System.currentTimeMillis()) {
        val failures = (prefs.getInt("${prefix}_failures", 0) + 1).coerceAtMost(30)
        val delay = if (failures < 5) 0L else
            (30_000L shl (failures - 5).coerceAtMost(5)).coerceAtMost(MAX_DELAY_MS)
        check(prefs.edit().putInt("${prefix}_failures", failures)
            .putLong("${prefix}_until", now + delay).commit())
    }

    fun reset() {
        check(prefs.edit().remove("${prefix}_failures").remove("${prefix}_until").commit())
    }

    private companion object { const val MAX_DELAY_MS = 15 * 60 * 1000L }
}
