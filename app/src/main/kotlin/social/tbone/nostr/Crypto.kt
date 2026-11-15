package social.tbone.nostr

import fr.acinq.secp256k1.Secp256k1
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest

/**
 * BIP-340 Schnorr signature verification over secp256k1.
 * Nostr uses x-only public keys and Schnorr signatures per NIP-01.
 *
 * Uses native JNI `libsecp256k1` (`secp256k1-kmp`) on Android devices for
 * 10x–30x faster verification, with an automatic pure-Java BouncyCastle
 * fallback for JVM unit tests and any environment without the native library.
 *
 * This does NOT handle key generation or signing — that is always
 * delegated to an external signer (Amber / nsecBunker) or LocalKeySigner.
 */
object Crypto {

    private val nativeSecp256k1: Secp256k1? by lazy {
        runCatching { Secp256k1.get() }.getOrNull()
    }

    private val sha256Local = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private val secp256k1 by lazy {
        // No JCA provider is registered or required here. Everything below
        // (SECNamedCurves / ECDomainParameters / ECPoint) is BouncyCastle's
        // lightweight API and resolves the curve itself, and taggedHash() uses
        // the platform's own SHA-256.
        val params = requireNotNull(SECNamedCurves.getByName("secp256k1")) {
            "secp256k1 curve unavailable"
        }
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    }

    private val challengeTagHash: ByteArray by lazy {
        sha256Local.get()!!.apply { reset() }.digest("BIP0340/challenge".toByteArray(Charsets.UTF_8))
    }

    private val liftXExponent: BigInteger by lazy {
        secp256k1.curve.field.characteristic.add(BigInteger.ONE).shiftRight(2)
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

        val native = nativeSecp256k1
        if (native != null) {
            return runCatching {
                // Note secp256k1-kmp parameter order: (signature, data, pub)
                native.verifySchnorr(signature, message, pubkey)
            }.getOrDefault(false)
        }

        return verifySchnorrBouncyCastle(message, signature, pubkey)
    }

    /** Pure-Java BouncyCastle BIP-340 verifier used as fallback when native JNI is unavailable. */
    internal fun verifySchnorrBouncyCastle(
        message: ByteArray,
        signature: ByteArray,
        pubkey: ByteArray,
    ): Boolean {
        return runCatching {
            val curve = secp256k1.curve
            val n = secp256k1.n
            val G = secp256k1.g

            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))

            if (r >= curve.field.characteristic || s >= n) return false

            // Lift x-only pubkey to curve point (BIP-340: even y)
            val P = liftX(BigInteger(1, pubkey)) ?: return false

            val digest = sha256Local.get()!!.apply { reset() }
            digest.update(challengeTagHash)
            digest.update(challengeTagHash)
            digest.update(signature, 0, 32)
            digest.update(pubkey)
            digest.update(message)
            val eBig = BigInteger(1, digest.digest()).mod(n)

            val R = G.multiply(s).subtract(P.multiply(eBig)).normalize()

            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().testBit(0)) return false
            if (R.affineXCoord.toBigInteger() != r) return false

            true
        }.getOrDefault(false)
    }

    private val TWO: BigInteger = BigInteger.valueOf(2L)
    private val THREE: BigInteger = BigInteger.valueOf(3L)
    private val SEVEN: BigInteger = BigInteger.valueOf(7L)

    /** Lifts an x coordinate to a secp256k1 curve point with even y (BIP-340). */
    private fun liftX(x: BigInteger): ECPoint? {
        val curve = secp256k1.curve
        val p = curve.field.characteristic
        if (x >= p) return null
        val y2 = x.modPow(THREE, p).add(SEVEN).mod(p)
        val y = y2.modPow(liftXExponent, p)
        if (y.modPow(TWO, p) != y2) return null
        val yEven = if (!y.testBit(0)) y else p.subtract(y)
        return curve.createPoint(x, yEven)
    }
}
