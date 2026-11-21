package social.tbone.media.audio

import social.tbone.settings.CachePreset

object AudioCachePolicy {
    const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1000
    const val FAILURE_TTL_MS = 5L * 60 * 1000
    const val MAX_RECORD_BYTES = 384 * 1024L
    fun diskBudget(preset: CachePreset): Long = (preset.mediaDiskBytes / 32).coerceIn(1024 * 1024L, 16 * 1024 * 1024L)
    fun key(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun fresh(saved: Long, now: Long, success: Boolean): Boolean =
        now >= saved && now - saved < if (success) SUCCESS_TTL_MS else FAILURE_TTL_MS
}
