package social.tbone.nostr.geohash

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives a stable-but-unlinkable Nostr identity per geohash channel,
 * mirroring Amethyst's GeohashKeyDerivation (Bitchat's location-channel
 * identity scheme):
 *
 *   privKey = HMAC-SHA256(seed, geohash || counterBE), incrementing the
 *   counter until the 32-byte output is a valid secp256k1 scalar.
 *
 * The seed is a per-install random secret (like Amethyst's remote-signer
 * path), so derived keys are deterministic on this device, unlinkable to the
 * account npub, and different per geohash cell.
 */
object GeohashKeyDerivation {
    const val ALGORITHM = "HmacSHA256"
    const val MAX_ITERATIONS = 10

    private val secp256k1 by lazy { SECNamedCurves.getByName("secp256k1") }
    private val domain by lazy {
        ECDomainParameters(secp256k1.curve, secp256k1.g, secp256k1.n, secp256k1.h)
    }

    fun derivePrivateKey(seed: ByteArray, geohash: String): ByteArray {
        val geoBytes = geohash.encodeToByteArray()
        for (counter in 0 until MAX_ITERATIONS) {
            val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(seed, ALGORITHM)) }
            mac.update(geoBytes)
            mac.update(counterBytesBE(counter))
            val candidate = mac.doFinal()
            if (isPrivateKeyValid(candidate)) return candidate
        }
        return MessageDigest.getInstance("SHA-256").digest(seed + geoBytes)
    }

    /** The BIP-340 public key (x-coordinate, 32 bytes) for a private key. */
    fun pubkeyBytesFromPrivkey(privkey: ByteArray): ByteArray {
        val d = BigInteger(1, privkey)
        val p = domain.g.multiply(d).normalize()
        return p.affineXCoord.encoded
    }

    fun isPrivateKeyValid(privkey: ByteArray): Boolean {
        val d = BigInteger(1, privkey)
        return d.compareTo(BigInteger.ONE) >= 0 && d.compareTo(domain.n) < 0
    }

    private fun counterBytesBE(counter: Int) =
        byteArrayOf(
            (counter ushr 24).toByte(),
            (counter ushr 16).toByte(),
            (counter ushr 8).toByte(),
            counter.toByte(),
        )

    // ── BIP-340 Schnorr signing (mirrors LocalKeySigner) ──────────────────────

    private val auxRand = ThreadLocal.withInitial { SecureRandom() }

    fun schnorrSign(message: ByteArray, privkey: ByteArray): String {
        val G = domain.g
        val n = domain.n
        val d = BigInteger(1, privkey)
        val P = G.multiply(d).normalize()
        val dScalar = if (P.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO)
            n.subtract(d) else d

        val randA = ByteArray(32).also { auxRand.get().nextBytes(it) }
        val t = dScalar.toBytes32().xorBytes(taggedHash("BIP0340/aux", randA))
        val rand = taggedHash("BIP0340/nonce", t + P.affineXCoord.encoded + message)
        val k0 = BigInteger(1, rand).mod(n)
        check(k0 != BigInteger.ZERO) { "Generated nonce is zero — retry" }

        val R = G.multiply(k0).normalize()
        val k = if (R.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO)
            n.subtract(k0) else k0

        val rx = R.affineXCoord.encoded
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + P.affineXCoord.encoded + message)).mod(n)
        val s = k.add(e.multiply(dScalar)).mod(n)

        return (rx + s.toBytes32()).toHex()
    }

    private fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(message)
        return digest.digest()
    }

    private fun BigInteger.toBytes32(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size == 33 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, 33)
        else if (bytes.size < 32) ByteArray(32 - bytes.size) + bytes
        else if (bytes.size == 32) bytes
        else bytes.copyOfRange(bytes.size - 32, bytes.size)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.xorBytes(other: ByteArray): ByteArray =
        ByteArray(size) { i -> (this[i].toInt() xor other[i].toInt()).toByte() }
}
