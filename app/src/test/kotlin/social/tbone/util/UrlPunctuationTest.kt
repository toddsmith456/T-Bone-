package social.tbone.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPunctuationTest {
    @Test fun trimsSentencePunctuation() {
        assertEquals("https://example.com/image.jpg", trimUrlPunctuation("https://example.com/image.jpg,"))
        assertEquals("https://example.com/page", trimUrlPunctuation("https://example.com/page."))
        assertEquals("https://example.com/a_(b)", trimUrlPunctuation("https://example.com/a_(b))."))
    }
    @Test fun sharedTokenizerKeepsBalancedBracketsAndLeavesSurroundingPunctuation() {
        val text = "See (https://example.com/a_(b)). Next https://example.com/path%29?x=a%2Cb, and [https://example.com/file]."
        val urls = httpUrlTokens(text)
        assertEquals(listOf("https://example.com/a_(b)", "https://example.com/path%29?x=a%2Cb", "https://example.com/file"), urls.map { it.url })
        urls.forEach { assertEquals(it.url, text.substring(it.start, it.endExclusive)) }
    }

    @Test fun stripsSurroundingPunctuationButNotEncodedOrBalancedCharacters() {
        assertEquals("https://e.test/a[b]", trimUrlPunctuation("https://e.test/a[b]]."))
        assertEquals("https://e.test/a%2E", trimUrlPunctuation("https://e.test/a%2E."))
        assertEquals("https://e.test/path?q=one%2C", trimUrlPunctuation("https://e.test/path?q=one%2C,"))
        assertEquals("https://e.test/a(b)", trimUrlPunctuation("https://e.test/a(b)))."))
    }

    @Test fun cleaningTrackingParamsPreservesProseAndBalancedUrlPath() {
        assertEquals("Read (https://e.test/a(b)?keep=1), thanks.",
            LinkCleaner.cleanUrlsInText("Read (https://e.test/a(b)?utm_source=foo&keep=1), thanks."))
    }
}
