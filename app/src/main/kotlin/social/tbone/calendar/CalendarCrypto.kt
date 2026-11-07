package social.tbone.calendar

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device encryption for calendar events.
 *
 * Each event's title and description are encrypted with **AES-256-GCM** using a
 * key that lives only in the **Android Keystore** (hardware-backed where the
 * device supports it; the key never leaves secure hardware and is never
 * written to disk in plaintext). Only ciphertext + IV are stored in Room — the
 * raw event text never touches storage.
 *
 * The calendar uses its own key alias ([KEY_ALIAS]) so wiping notes never
 * affects calendar events and vice versa.
 */
@Singleton
class CalendarCrypto internal constructor(private val alias: String) {
    @Inject constructor() : this(KEY_ALIAS)

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey = synchronized(KEY_CREATION_LOCK) {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        generator.generateKey()
    }

    /** Encrypts plaintext → (ciphertext, iv). Throws on failure. */
    fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ciphertext to cipher.iv
    }

    /** Decrypts (ciphertext, iv) → plaintext. Throws on tamper/wrong key. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            (keyStore.getKey(alias, null) as? SecretKey
                ?: throw java.security.KeyStoreException("Encryption key is unavailable")),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /** Encrypt→decrypt round-trip; the calendar only claims encryption when true. */
    fun selfTest(): Boolean = runCatching {
        val (ct, iv) = encrypt(SELF_TEST_PLAINTEXT)
        decrypt(ct, iv) == SELF_TEST_PLAINTEXT
    }.getOrDefault(false)

    /** True when the key lives in secure hardware (TEE / StrongBox). */
    fun isHardwareBacked(): Boolean = runCatching {
        val key = getOrCreateKey()
        val factory = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isInsideSecureHardware
    }.getOrDefault(false)

    companion object {
        private val KEY_CREATION_LOCK = Any()
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tbone_calendar_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val SELF_TEST_PLAINTEXT = "t-bone-calendar-encryption-self-test"
    }
}
