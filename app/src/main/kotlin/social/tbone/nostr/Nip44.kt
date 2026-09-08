package social.tbone.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

/**
 * NIP-44 v2 versioned encryption.
 *
 * Scheme: ChaCha20 + HMAC-SHA256 with an ECDH-derived conversation key.
 * Spec: https://github.com/nostr-protocol/nips/blob/master/44.md
 */
object Nip44 {

    private const val VERSION: Byte = 0x02
    private val random = SecureRandom()

    private val secp256k1 by lazy {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val params = SECNamedCurves.getByName("secp256k1")
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    }

    /**
     * Encrypts [plaintext] using the shared secret derived from
     * [senderPrivkey] (32-byte hex) and [recipientPubkey] (32-byte x-only hex).
     * Returns base64-encoded ciphertext.
     */
    fun encrypt(plaintext: String, senderPrivkey: ByteArray, recipientPubkey: ByteArray): String {
        val conversationKey = conversationKey(senderPrivkey, recipientPubkey)
        val nonce = ByteArray(32).also { random.nextBytes(it) }
        return encrypt(plaintext.toByteArray(Charsets.UTF_8), conversationKey, nonce)
    }

    /**
     * Decrypts a NIP-44 v2 [payload] (base64) using the shared secret derived from
     * [recipientPrivkey] and [senderPubkey].
     */
    fun decrypt(payload: String, recipientPrivkey: ByteArray, senderPubkey: ByteArray): String {
        val conversationKey = conversationKey(recipientPrivkey, senderPubkey)
        return decrypt(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), conversationKey)
    }

    // ── Core crypto ───────────────────────────────────────────────────────────

    private fun conversationKey(privkey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        val P = liftX(BigInteger(1, xOnlyPubkey))
            ?: throw IllegalArgumentException("Invalid x-only pubkey")
        val sharedPoint = P.multiply(BigInteger(1, privkey)).normalize()
        val sharedX = sharedPoint.affineXCoord.encoded // 32 bytes
        // conversation_key = HKDF-SHA256(ikm=sharedX, salt="nip44-v2", info="")
        val hkdf = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        hkdf.init(HKDFParameters(sharedX, "nip44-v2".toByteArray(), ByteArray(0)))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    private fun encrypt(plaintext: ByteArray, conversationKey: ByteArray, nonce: ByteArray): String {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val padded = pad(plaintext)
        val ciphertext = chaCha20(chachaKey, chachaNonce, padded)
        val mac = hmacSha256(hmacKey, nonce + ciphertext)
        val output = byteArrayOf(VERSION) + nonce + ciphertext + mac
        return android.util.Base64.encodeToString(output, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(payload: ByteArray, conversationKey: ByteArray): String {
        // version(1) + nonce(32) + ciphertext(≥34: 2-byte length + 32-byte min chunk) + mac(32) = 99
        require(payload.size >= 99) { "NIP-44 payload too short: ${payload.size} bytes" }
        check(payload[0] == VERSION) { "Unsupported NIP-44 version: ${payload[0]}" }
        val nonce = payload.sliceArray(1..32)
        val mac = payload.sliceArray(payload.size - 32 until payload.size)
        val ciphertext = payload.sliceArray(33 until payload.size - 32)

        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val expectedMac = hmacSha256(hmacKey, nonce + ciphertext)
        // Constant-time comparison to avoid timing side-channels.
        check(MessageDigest.isEqual(mac, expectedMac)) { "NIP-44 MAC verification failed" }

        return unpad(chaCha20(chachaKey, chachaNonce, ciphertext)).toString(Charsets.UTF_8)
    }

    private data class MessageKeys(
        val chachaKey: ByteArray,
        val chachaNonce: ByteArray,
        val hmacKey: ByteArray,
    )

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): MessageKeys {
        val hkdf = HKDFBytesGenerator(org.bouncycastle.crypto.digests.SHA256Digest())
        hkdf.init(HKDFParameters(conversationKey, nonce, "nip44-v2".toByteArray()))
        val output = ByteArray(76).also { hkdf.generateBytes(it, 0, 76) }
        return MessageKeys(
            chachaKey = output.sliceArray(0..31),
            chachaNonce = output.sliceArray(32..43),
            hmacKey = output.sliceArray(44..75),
        )
    }

    private fun chaCha20(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return ByteArray(input.size).also { engine.processBytes(input, 0, input.size, it, 0) }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, "HmacSHA256"))
        }.doFinal(data)

    /** NIP-44 padding: 2-byte big-endian length prefix + zero padding to next chunk boundary. */
    private fun pad(plaintext: ByteArray): ByteArray {
        val len = plaintext.size
        check(len in 1..65535) { "Plaintext length out of range: $len" }
        val chunkSize = when {
            len <= 32 -> 32
            else -> {
                val nextPow2 = Integer.highestOneBit(len - 1) shl 1
                max(32, nextPow2 / 8)
            }
        }
        val padded = ByteArray(2 + chunkSize * ((len / chunkSize) + if (len % chunkSize != 0) 1 else 0))
        padded[0] = (len shr 8).toByte()
        padded[1] = (len and 0xFF).toByte()
        plaintext.copyInto(padded, 2)
        return padded
    }

    private fun unpad(padded: ByteArray): ByteArray {
        val len = ((padded[0].toInt() and 0xFF) shl 8) or (padded[1].toInt() and 0xFF)
        return padded.sliceArray(2 until 2 + len)
    }

    private fun liftX(x: BigInteger): ECPoint? {
        val curve = secp256k1.curve
        val p = curve.field.characteristic
        if (x >= p) return null
        val y2 = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = y2.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
        if (y.modPow(BigInteger.TWO, p) != y2) return null
        val yEven = if (y.mod(BigInteger.TWO) == BigInteger.ZERO) y else p.subtract(y)
        return curve.createPoint(x, yEven)
    }
}
