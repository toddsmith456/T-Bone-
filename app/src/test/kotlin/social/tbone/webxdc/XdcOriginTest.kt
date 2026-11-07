package social.tbone.webxdc

import org.junit.Assert.*
import org.junit.Test

class XdcOriginTest {
    @Test fun onlyExactHttpsOriginIsInternal() {
        val host = "abc.xdc.local"
        assertTrue(isXdcOrigin("https", host, -1, null, host))
        assertTrue(isXdcOrigin("HTTPS", "ABC.XDC.LOCAL", 443, null, host))
        assertFalse(isXdcOrigin("http", host, -1, null, host))
        assertFalse(isXdcOrigin("https", host, 444, null, host))
        assertFalse(isXdcOrigin("https", host, -1, "user", host))
        assertFalse(isXdcOrigin("https", "evil.$host", -1, null, host))
        assertFalse(isXdcOrigin(null, null, -1, null, host))
    }
}
