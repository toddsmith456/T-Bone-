package social.tbone.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes device-local HMAC-SHA256 digests for `media_scan_cache` primary keys
 * so the SQLite database never stores plaintext media URLs or file hashes.
 *
 * Uses a non-exportable `AndroidKeyStore` HMAC-SHA256 key (`tbone_scan_cache_key`)
 * that is deleted by `DataWiper` on duress wipe. Falls back cleanly to a process-
 * ephemeral random key in JVM unit tests or environments without AndroidKeyStore.
 */
@Singleton
class ScanKeyHasher @Inject constructor() {

    companion object {
        const val KEYSTORE_ALIAS = "tbone_scan_cache_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val HMAC_ALGO = "HmacSHA256"
    }

    @Volatile
    private var cachedKey: SecretKey? = null

    fun hmacHex(rawIdentifier: String): String {
        val key = getOrCreateKey()
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(key)
        val digest = mac.doFinal(rawIdentifier.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { b -> "%02x".format(b) }
    }

    /** Called during duress wipe after the keystore entry is deleted. */
    fun clearInMemoryKey() {
        cachedKey = null
    }

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val created = loadOrCreateKeystoreKey() ?: createEphemeralFallbackKey()
            cachedKey = created
            return created
        }
    }

    private fun loadOrCreateKeystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return@runCatching it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(256)
                .build(),
        )
        keyGen.generateKey()
    }.onFailure { err ->
        Timber.d(err, "AndroidKeyStore unavailable for scan cache HMAC; using ephemeral key")
    }.getOrNull()

    private fun createEphemeralFallbackKey(): SecretKey {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return SecretKeySpec(bytes, HMAC_ALGO)
    }
}
