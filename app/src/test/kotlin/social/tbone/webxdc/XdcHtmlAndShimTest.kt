package social.tbone.webxdc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XdcHtmlAndShimTest {

    @Test
    fun injectsBridgeAsFirstHeadChild() {
        val html = "<!DOCTYPE html><html><head><title>t</title><script src=\"app.js\"></script></head><body></body></html>"
        val out = XdcHtml.injectBridge(html)
        val head = out.indexOf("<head>")
        val script = out.indexOf("<script src=\"webxdc.js\"></script>")
        val appScript = out.indexOf("<script src=\"app.js\"></script>")
        assertTrue(script in (head + 1)..(appScript - 1))
        assertEquals(html.length + "<script src=\"webxdc.js\"></script>".length, out.length)
    }

    @Test
    fun doesNotInjectTwice() {
        val html = "<html><head><script src=\"webxdc.js\"></script></head><body></body></html>"
        assertEquals(html, XdcHtml.injectBridge(html))
    }

    @Test
    fun injectsWhenThereIsNoHead() {
        val out = XdcHtml.injectBridge("<body>hi</body>")
        assertTrue(out.startsWith("<script src=\"webxdc.js\"></script>"))
    }

    @Test
    fun cspForbidsExternalOrigins() {
        assertTrue(XdcHtml.CSP.contains("default-src 'self'"))
        assertFalse(XdcHtml.CSP.contains("http://"))
        assertFalse(XdcHtml.CSP.contains("https://"))
    }

    @Test
    fun shimExposesTheWholeWebxdcSurface() {
        val js = WebxdcShim.build("npub1abc", "alice")
        for (fn in listOf(
            "sendUpdate",
            "setUpdateListener",
            "getAllUpdates",
            "sendToChat",
            "importFiles",
            "joinRealtimeChannel",
        )) {
            assertTrue("missing $fn", js.contains("$fn: function"))
        }
        assertTrue(js.contains("selfAddr: \"npub1abc\""))
        assertTrue(js.contains("selfName: \"alice\""))
        assertTrue(js.contains("TBoneXdc.call"))
        assertTrue(js.contains("sendUpdateMaxSize: 128000"))
    }

    @Test
    fun shimEscapesHostileIdentityStrings() {
        val js = WebxdcShim.build("npub\");alert(1);//", "a</script><script>alert(1)</script>")
        // The quote must be backslash-escaped, so it cannot terminate the literal.
        assertTrue(js.contains("selfAddr: \"npub\\\");alert(1);//\""))
        assertFalse(js.contains("</script>"))
        assertTrue(js.contains("\\u003c"))
    }

    @Test
    fun jsQuoteEscapesEverythingDangerous() {
        val quoted = WebxdcBridge.jsQuote("a\"b\\c\nd\u2028e</script>")
        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
        assertTrue(quoted.contains("\\\""))
        assertTrue(quoted.contains("\\\\"))
        assertTrue(quoted.contains("\\n"))
        assertTrue(quoted.contains("\\u2028"))
        assertTrue(quoted.contains("\\u003c"))
    }
}
