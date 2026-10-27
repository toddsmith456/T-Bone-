package social.tbone.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Strong PIN hashing — replaces plain SHA-256.
 *
 * New hashes are `v1$iterations$saltHex$hashHex` where hash = PBKDF2WithHmacSHA256(pin, salt).
 * Legacy hashes are 64-char hex SHA-256(pin) with no `$` — still verified, then auto-upgraded.
 *
 * This is not “unbreakable” for a 4-digit PIN (10k space), but PBKDF2 with 120k iterations +
 * random 16-byte salt raises offline brute-force from <1 ms to seconds per guess and defeats rainbow tables.
 *
 * Rate limiting in AppLockManager/ToolboxSecurity adds the remaining wall.
 */
object PinHasher {

    private const val VERSION = "v1"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val HASH_BITS = 256

    fun hash(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt, ITERATIONS)
        return "$VERSION\$$ITERATIONS\$${salt.toHex()}\$${hash.toHex()}"
    }

    fun verify(pin: String, stored: String): Boolean {
        // Legacy SHA-256 (64 hex, no $)
        if (!stored.contains("$")) {
            return sha256(pin) == stored
        }
        // New format: v1$iter$saltHex$hashHex
        val parts = stored.split("$")
        if (parts.size != 4 || parts[0] != VERSION) return false
        val iter = parts[1].toIntOrNull() ?: return false
        val salt = parts[2].hexToBytes() ?: return false
        val expected = parts[3].hexToBytes() ?: return false
        val computed = pbkdf2(pin, salt, iter)
        return constantTimeEquals(computed, expected)
    }

    fun isLegacy(stored: String): Boolean = !stored.contains("$")

    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, HASH_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return skf.generateSecret(spec).encoded
    }

    private fun sha256(input: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray? = runCatching {
        val s = trim()
        if (s.length % 2 != 0) return null
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
