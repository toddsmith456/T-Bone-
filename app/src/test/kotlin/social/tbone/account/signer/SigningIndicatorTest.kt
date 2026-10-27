package social.tbone.account.signer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningIndicatorTest {
    @Test
    fun `concurrent signer requests are reference counted`() {
        val indicator = SigningIndicator()
        assertFalse(indicator.signingInProgress.value)

        indicator.start() // publish
        indicator.start() // background relay AUTH
        assertTrue(indicator.signingInProgress.value)

        indicator.stop()
        assertTrue("first completion must not clear the second request", indicator.signingInProgress.value)

        indicator.stop()
        assertFalse(indicator.signingInProgress.value)
    }

    @Test
    fun `an extra stop cannot leave the overlay stuck`() {
        val indicator = SigningIndicator()
        indicator.start()
        indicator.stop()
        indicator.stop()

        assertFalse(indicator.signingInProgress.value)
    }
}
