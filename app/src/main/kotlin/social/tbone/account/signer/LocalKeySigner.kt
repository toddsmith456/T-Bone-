package social.tbone.account.signer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import social.tbone.nostr.Event
import social.tbone.nostr.Nip44
import social.tbone.nostr.UnsignedEvent
import social.tbone.nostr.hexToBytes
import social.tbone.nostr.toHex
import java.math.BigInteger
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE_ALIAS = "tbone_local_key_encryption"
private const val KEY_PROVIDER = "AndroidKeyStore"
private const val GCM_IV_LEN = 12
private const val GCM_TAG_LEN = 128

/**
 * Last-resort signer that holds a secp256k1 private key encrypted with an
 * Android Keystore-backed AES-GCM key.
 *
 * Android Keystore does not support secp256k1 natively, so we:
 *   1. Generate the secp256k1 keypair via Bouncy Castle
 *   2. Encrypt the raw private key bytes with AES-GCM using a Keystore key
 *   3. Store the encrypted blob in DataStore
 *
 * The private key is only in memory for the duration of a signing call.
 *
 * Also used as the session signer for NsecBunkerSigner — it signs the
 * NIP-46 wrapper events with the ephemeral session key, not the user's key.
 */
class LocalKeySigner(
    override val pubkey: String,
    private val encryptedPrivkey: ByteArray,
) : NostrSigner {

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> = runCatching {
        val privkey = decryptPrivkey()
        val id = event.computeId()
        val sig = schnorrSign(id.hexToBytes(), privkey)
        Event(
            id = id,
            pubkey = pubkey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
            sig = sig,
        ).also {
            check(it.verify()) { "Self-signed event failed verification" }
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        runCatching {
            val privkey = decryptPrivkey()
            Nip44.encrypt(plaintext, privkey, recipientPubkey.hexToBytes())
        }

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        runCatching {
            val privkey = decryptPrivkey()
            Nip44.decrypt(ciphertext, privkey, senderPubkey.hexToBytes())
        }

    /** Synchronous variant used internally by NsecBunkerSigner response parsing. */
    fun nip44DecryptSync(ciphertext: String, senderPubkey: String): String? = runCatching {
        val privkey = decryptPrivkey()
        Nip44.decrypt(ciphertext, privkey, senderPubkey.hexToBytes())
    }.getOrNull()

    // ── Key management ────────────────────────────────────────────────────────

    private fun decryptPrivkey(): ByteArray {
        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
        val key = keyStore.getKey(KEYSTORE_ALIAS, null) as? javax.crypto.SecretKey
            ?: error("Keystore key not found — was this account set up on a different device?")

        val iv = encryptedPrivkey.sliceArray(0 until GCM_IV_LEN)
        val ciphertext = encryptedPrivkey.sliceArray(GCM_IV_LEN until encryptedPrivkey.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LEN, iv))
        return cipher.doFinal(ciphertext)
    }

    // ── BIP-340 Schnorr signing ───────────────────────────────────────────────

    private fun schnorrSign(message: ByteArray, privkey: ByteArray): String {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val params = SECNamedCurves.getByName("secp256k1")
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val G = domain.g
        val n = domain.n

        val d = BigInteger(1, privkey)
        // BIP-340: negate privkey if P.y is odd
        val P = G.multiply(d).normalize()
        val dScalar = if (P.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO)
            n.subtract(d) else d

        // Deterministic nonce per BIP-340 (RFC 6979 variant with tagged hash)
        val randA = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = dScalar.toBytes32() xor taggedHash("BIP0340/aux", randA)
        val rand = taggedHash("BIP0340/nonce", t + P.affineXCoord.encoded + message)
        val k0 = BigInteger(1, rand).mod(n)
        check(k0 != BigInteger.ZERO) { "Generated nonce is zero — retry" }

        val R = G.multiply(k0).normalize()
        val k = if (R.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO)
            n.subtract(k0) else k0

        val rx = R.affineXCoord.encoded
        val e = BigInteger(1,
            taggedHash("BIP0340/challenge", rx + P.affineXCoord.encoded + message)
        ).mod(n)
        val s = k.add(e.multiply(dScalar)).mod(n)

        return (rx + s.toBytes32()).toHex()
    }

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.reset()
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(message)
        return digest.digest()
    }

    private fun BigInteger.toBytes32(): ByteArray {
        val b = toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.takeLast(32).toByteArray()
            else -> ByteArray(32 - b.size) + b
        }
    }

    private infix fun ByteArray.xor(other: ByteArray): ByteArray {
        val a = this
        return ByteArray(size) { (a[it].toInt() xor other[it].toInt()).toByte() }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        /**
         * Generates a new secp256k1 keypair, encrypts the private key with the
         * Android Keystore, and returns a [LocalKeySigner] + the encrypted blob
         * to persist in DataStore.
         */
        fun generate(): Pair<LocalKeySigner, ByteArray> {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
            val params = SECNamedCurves.getByName("secp256k1")
            val n = params.n

            var privkeyInt: BigInteger
            do {
                privkeyInt = BigInteger(256, SecureRandom())
            } while (privkeyInt <= BigInteger.ZERO || privkeyInt >= n)

            val privkey = privkeyInt.toBytes32()
            val pubkeyPoint = params.g.multiply(privkeyInt).normalize()
            val pubkey = pubkeyPoint.affineXCoord.encoded.toHex() // x-only

            val encrypted = encryptPrivkey(privkey)
            return LocalKeySigner(pubkey, encrypted) to encrypted
        }

        private fun BigInteger.toBytes32(): ByteArray {
            val b = toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.takeLast(32).toByteArray()
                else -> ByteArray(32 - b.size) + b
            }
        }

        private fun encryptPrivkey(privkey: ByteArray): ByteArray {
            ensureKeystoreKey()
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
            val key = keyStore.getKey(KEYSTORE_ALIAS, null) as javax.crypto.SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(privkey)
            return iv + ciphertext
        }

        private fun ensureKeystoreKey() {
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEYSTORE_ALIAS)) return
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEY_PROVIDER).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .build()
                )
            }.generateKey()
            Timber.d("Generated Keystore key: $KEYSTORE_ALIAS")
        }
    }
}
