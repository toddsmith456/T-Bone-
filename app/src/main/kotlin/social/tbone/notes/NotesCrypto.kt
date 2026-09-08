package social.tbone.notes

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
 * On-device encryption for the Notes tab.
 *
 * Every note is encrypted with **AES-256-GCM** using a key that lives only in
 * the **Android Keystore** (hardware-backed where the device supports it; the
 * key never leaves secure hardware and is never written to disk in plaintext).
 * Only the ciphertext + IV are stored in the local database — the raw note
 * text never touches storage.
 *
 * Verification: [selfTest] runs a full encrypt→decrypt round-trip at startup
 * and returns whether it produced the original plaintext. It is invoked from
 * the notes screen, which shows a "encrypted" badge only when the test passes.
 */
@Singleton
class NotesCrypto @Inject constructor() {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** Encrypts plaintext → (ciphertext, iv). Throws on failure. */
    fun encrypt(plaintext: String): Pair<ByteArray, ByteArray> =
        encryptBytes(plaintext.toByteArray(Charsets.UTF_8))

    /** Decrypts (ciphertext, iv) → plaintext. Throws on tamper/wrong key. */
    fun decrypt(ciphertext: ByteArray, iv: ByteArray): String =
        String(decryptBytes(ciphertext, iv), Charsets.UTF_8)

    /** Encrypts arbitrary bytes (used for voice/image attachments). */
    fun encryptBytes(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(bytes)
        return ciphertext to cipher.iv
    }

    /** Decrypts arbitrary bytes (used for voice/image attachments). */
    fun decryptBytes(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * End-to-end verification: encrypts a known string, decrypts it, and
     * returns true only if the round-trip reproduces the original bytes.
     * Called at app start; the notes screen shows its "encrypted" badge only
     * when this passes.
     */
    fun selfTest(): Boolean = runCatching {
        val (ct, iv) = encrypt(SELF_TEST_PLAINTEXT)
        decrypt(ct, iv) == SELF_TEST_PLAINTEXT
    }.getOrDefault(false)

    /**
     * True when the key lives in secure hardware (TEE / StrongBox) and the
     * raw key bytes never leave the chip. Falls back to false on devices
     * where the key is software-backed inside the Keystore.
     */
    fun isHardwareBacked(): Boolean = runCatching {
        val key = getOrCreateKey()
        val factory = KeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
        val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
        info.isInsideSecureHardware
    }.getOrDefault(false)

    /** Human-readable cipher description for the encryption info screen. */
    val cipherLabel: String get() = TRANSFORMATION

    /** Key alias in the Android Keystore. */
    val keyAlias: String get() = KEY_ALIAS

    /** GCM auth-tag length in bits. */
    val tagBits: Int get() = GCM_TAG_BITS

    /** Random IV length in bytes. */
    val ivLength: Int get() = GCM_IV_BYTES

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tbone_notes_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
        const val SELF_TEST_PLAINTEXT = "t-bone-notes-encryption-self-test"
    }
}
