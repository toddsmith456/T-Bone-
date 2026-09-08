package social.tbone.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.Security

/**
 * BIP-340 Schnorr signature verification over secp256k1.
 * Nostr uses x-only public keys and Schnorr signatures per NIP-01.
 *
 * This does NOT handle key generation or signing — that is always
 * delegated to an external signer (Amber / nsecBunker).
 */
object Crypto {

    private val secp256k1 by lazy {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val params = SECNamedCurves.getByName("secp256k1")
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    }

    /**
     * Verifies a BIP-340 Schnorr signature.
     *
     * @param message   32-byte message hash (the event id as bytes)
     * @param signature 64-byte Schnorr signature (r || s)
     * @param pubkey    32-byte x-only public key
     */
    fun verifySchnorr(message: ByteArray, signature: ByteArray, pubkey: ByteArray): Boolean {
        require(message.size == 32) { "Message must be 32 bytes" }
        require(signature.size == 64) { "Signature must be 64 bytes" }
        require(pubkey.size == 32) { "x-only pubkey must be 32 bytes" }

        return runCatching {
            val curve = secp256k1.curve
            val n = secp256k1.n
            val G = secp256k1.g

            val r = BigInteger(1, signature.sliceArray(0..31))
            val s = BigInteger(1, signature.sliceArray(32..63))

            if (r >= curve.field.characteristic || s >= n) return false

            // Lift x-only pubkey to curve point (BIP-340: even y)
            val P = liftX(BigInteger(1, pubkey)) ?: return false

            val e = taggedHash("BIP0340/challenge", r.toBytes32() + pubkey + message)
            val eBig = BigInteger(1, e).mod(n)

            val R = G.multiply(s).subtract(P.multiply(eBig)).normalize()

            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO) return false
            if (R.affineXCoord.toBigInteger() != r) return false

            true
        }.getOrDefault(false)
    }

    /** Lifts an x coordinate to a secp256k1 curve point with even y (BIP-340). */
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

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || message) */
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
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.takeLast(32).toByteArray()
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}
