package social.tbone.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlPunctuationTest {
    @Test fun trimsSentencePunctuation() {
        assertEquals("https://example.com/image.jpg", trimUrlPunctuation("https://example.com/image.jpg,"))
        assertEquals("https://example.com/page", trimUrlPunctuation("https://example.com/page."))
        assertEquals("https://example.com/a_(b)", trimUrlPunctuation("https://example.com/a_(b))."))
    }
}
