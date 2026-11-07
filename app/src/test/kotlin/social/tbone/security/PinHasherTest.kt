package social.tbone.security

import org.junit.Assert.*
import org.junit.Test

class PinHasherTest {
    @Test fun samePinUsesRandomSaltAndCanBeVerified() {
        val first = PinHasher.hash("1234")
        val second = PinHasher.hash("1234")
        assertNotEquals(first, second)
        assertTrue(PinHasher.verify("1234", first))
        assertFalse(PinHasher.verify("4321", first))
    }
    @Test fun legacyHashVerifies() {
        val legacy = java.security.MessageDigest.getInstance("SHA-256").digest("1234".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue(PinHasher.isLegacy(legacy))
        assertTrue(PinHasher.verify("1234", legacy))
        assertFalse(PinHasher.verify("4321", legacy))
    }
}
