package social.tbone.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A mention label must resolve no matter what punctuation follows it, because
 * people write `@T-Bone's client`, `@T-Bone,`, `(@T-Bone)`, `@T-Bone!` and so on.
 */
class MentionResolutionTest {

    private fun one(text: String) = resolveOneMention(text, "T-Bone", "nostr:NPUB")

    @Test
    fun `the reported case resolves`() {
        assertEquals("nostr:NPUB's client", one("@T-Bone's client"))
    }

    @Test
    fun `every punctuation mark after a handle still resolves`() {
        val marks = listOf(
            "'", "'", ",", ".", ";", ":", "!", "?", ")", "]", "}", ">", "\"", "`",
            "-", "_", "—", "–", "…", "/", "\\", "|", "*", "&", "%", "$", "#", "+", "=",
            "~", "^", "@", "\u00AB", "\u201D", "\u3002", "\uFF0C", "🙂", "❤", "→",
        )
        marks.forEach { mark ->
            assertEquals(
                "handle followed by '$mark' should resolve, and the mark must survive",
                "nostr:NPUB$mark was right",
                one("@T-Bone$mark was right"),
            )
        }
    }

    @Test
    fun `opening punctuation before a handle still resolves`() {
        listOf("(", "[", "{", "<", "\"", "'", "`", "«", "“", "‘", "（", "「").forEach { mark ->
            assertEquals(
                "handle after '$mark' should resolve",
                "$mark nostr:NPUB",
                one("$mark @T-Bone"),
            )
        }
    }

    @Test
    fun `handle in the middle and at the end of a line resolves`() {
        assertEquals("hi nostr:NPUB there", one("hi @T-Bone there"))
        assertEquals("hi nostr:NPUB", one("hi @T-Bone"))
        assertEquals("nostr:NPUB wrote this", one("@T-Bone wrote this"))
    }

    @Test
    fun `every occurrence resolves, not only the first`() {
        assertEquals(
            "nostr:NPUB, nostr:NPUB and nostr:NPUB!",
            one("@T-Bone, @T-Bone and @T-Bone!"),
        )
    }

    @Test
    fun `a punctuation run that continues into a word is not our mention`() {
        // `.`/`-`/`_` are legal inside a handle, so `@T-Bone.xyz` and
        // `@T-Bone-Bone` are a longer handle being typed, not `T-Bone` plus a mark.
        assertEquals("@T-Bone.xyz", one("@T-Bone.xyz"))
        assertEquals("@T-Bone-Bone", one("@T-Bone-Bone"))
        assertEquals("@T-Bone_2", one("@T-Bone_2"))
        // ...but at the end of a word it is sentence punctuation.
        assertEquals("nostr:NPUB.", one("@T-Bone."))
        assertEquals("nostr:NPUB -", one("@T-Bone -"))
    }

    @Test
    fun `a longer handle is never partially resolved`() {
        // Longest-first ordering is what protects the longer label.
        val out = resolveOneMention("@T-Bonez hi", "T-Bone", "nostr:NPUB")
        assertEquals("@T-Bonez hi", out)
    }

    @Test
    fun `an email or url path is not a mention`() {
        assertEquals("mail me at me@T-Bone", one("mail me at me@T-Bone"))
        assertEquals("https://x.test/@T-Bone", one("https://x.test/@T-Bone"))
    }

    @Test
    fun `multi-word and apostrophe handles resolve`() {
        assertEquals(
            "nostr:NPUB's notes",
            resolveOneMention("@T-Bone's notes", "T-Bone", "nostr:NPUB"),
        )
        assertEquals(
            "nostr:NPUB hello",
            resolveOneMention("@Big Al hello", "Big Al", "nostr:NPUB"),
        )
    }

    @Test
    fun `text without the mention is untouched`() {
        assertEquals("nothing here", one("nothing here"))
        assertEquals("", one(""))
    }
}
