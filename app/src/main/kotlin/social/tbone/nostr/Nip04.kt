package social.tbone.nostr

import android.util.Base64
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.SecureRandom
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NIP-04 compatibility encryption used by older Nostr Wallet Connect services.
 * NIP-44 is preferred whenever the wallet advertises it; this implementation
 * exists only for the protocol's explicitly required backwards-compatibility
 * path.
 */
object Nip04 {
    private val random = SecureRandom()

    private val secp256k1 by lazy {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val params = SECNamedCurves.getByName("secp256k1")
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    }

    fun encrypt(plaintext: String, senderPrivkey: ByteArray, recipientPubkey: ByteArray): String {
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret(senderPrivkey, recipientPubkey), "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ciphertext, Base64.NO_WRAP) + "?iv=" +
            Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    fun decrypt(payload: String, recipientPrivkey: ByteArray, senderPubkey: ByteArray): String {
        val parts = payload.split("?iv=", limit = 2)
        require(parts.size == 2) { "Invalid NIP-04 payload" }
        val ciphertext = Base64.decode(parts[0], Base64.DEFAULT)
        val iv = Base64.decode(parts[1], Base64.DEFAULT)
        require(iv.size == 16) { "Invalid NIP-04 IV" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret(recipientPrivkey, senderPubkey), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun sharedSecret(privkey: ByteArray, xOnlyPubkey: ByteArray): ByteArray {
        require(privkey.size == 32 && xOnlyPubkey.size == 32) { "NIP-04 keys must be 32 bytes" }
        val point = liftX(BigInteger(1, xOnlyPubkey))
            ?: throw IllegalArgumentException("Invalid x-only pubkey")
        val shared = point.multiply(BigInteger(1, privkey)).normalize().affineXCoord.toBigInteger().toByteArray()
        // NIP-04 uses the ECDH x-coordinate directly as the AES-256 key.
        return when {
            shared.size == 32 -> shared
            shared.size > 32 -> shared.copyOfRange(shared.size - 32, shared.size)
            else -> ByteArray(32 - shared.size) + shared
        }
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
