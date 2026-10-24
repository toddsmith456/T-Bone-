package social.tbone.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.ProfileContent

class ProfileSearchTest {

    @Test
    fun compatibilityNormalizeHandlesMathematicalFontsFullwidthAndAccents() {
        assertEquals("alice", ProfileSearch.normalize("𝔄𝕝𝕚𝕔𝕖"))
        assertEquals("alice", ProfileSearch.normalize("Ａｌｉｃｅ"))
        assertEquals("jose", ProfileSearch.normalize("José"))
    }

    @Test
    fun matchesAcrossDisplayNameNameNip05AndAbout() {
        assertTrue(ProfileSearch.matches("alice", ProfileContent(displayName = "𝓐𝓵𝓲𝓬𝓮")))
        assertTrue(ProfileSearch.matches("alice", ProfileContent(name = "Ａｌｉｃｅ")))
        assertTrue(ProfileSearch.matches("example.com", ProfileContent(nip05 = "alice@example.com")))
        assertTrue(ProfileSearch.matches("photography", ProfileContent(about = "𝙿𝚑𝚘𝚝𝚘𝚐𝚛𝚊𝚙𝚑𝚢 and coffee")))
        assertFalse(ProfileSearch.matches("bob", ProfileContent(displayName = "𝓐𝓵𝓲𝓬𝓮")))
        assertFalse(ProfileSearch.matches(" ", ProfileContent(name = "Alice")))
    }

    @Test
    fun relayQueriesIncludeFancyUnicodeAndStayBounded() {
        val variants = ProfileSearch.relayQueryVariants("alice")
        assertTrue(variants.contains("alice"))
        assertTrue(variants.contains("𝐚𝐥𝐢𝐜𝐞"))
        assertTrue(variants.contains("𝓪𝓵𝓲𝓬𝓮"))
        assertTrue(variants.contains("𝔞𝔩𝔦𝔠𝔢"))
        assertTrue(variants.size <= 48)
    }
}
