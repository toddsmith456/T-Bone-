package social.tbone.ui.reactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emoji parsing for multi emoji reactions: whatever the user types on their
 * keyboard's emoji panel becomes their saved reaction set.
 */
class EmojiTest {

    @Test fun simpleEmojiIsKept() {
        assertEquals(listOf("🔥"), extractEmojis("🔥"))
    }

    @Test fun severalEmojisSplitIntoIndividualOnes() {
        assertEquals(listOf("👍", "🎉", "😀"), extractEmojis("👍🎉😀"))
    }

    @Test fun lettersAndDigitsAreIgnored() {
        assertEquals(listOf("❤"), extractEmojis("abc123❤"))
        assertTrue(extractEmojis("hello").isEmpty())
    }

    @Test fun skinToneStaysOneEmoji() {
        // 👍🏽 is a single grapheme cluster, not "👍" + a modifier.
        val result = extractEmojis("👍🏽")
        assertEquals(1, result.size)
        assertEquals("👍🏽", result.first())
    }

    @Test fun zwjSequenceStaysOneEmoji() {
        // Family emoji: four code points joined by ZWJ.
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        val result = extractEmojis(family)
        assertEquals(1, result.size)
        assertEquals(family, result.first())
    }

    @Test fun flagStaysOneEmoji() {
        val flag = "\uD83C\uDDEC\uD83C\uDDE7" // 🇬🇧
        assertEquals(listOf(flag), extractEmojis(flag))
    }

    @Test fun whitespaceAndNewlinesAreDropped() {
        assertEquals(listOf("😀", "😎"), extractEmojis("😀 \n 😎"))
    }

    @Test fun emptyInputYieldsNothing() {
        assertTrue(extractEmojis("").isEmpty())
        assertTrue(extractEmojis("   ").isEmpty())
    }

    @Test fun plainPlusAndMinusAreNotEmojiReactions() {
        // "+" is the classic like, "-" a dislike — neither is drawn as an emoji.
        assertFalse(isEmojiReaction("+"))
        assertFalse(isEmojiReaction("-"))
        assertFalse(isEmojiReaction(""))
        assertFalse(isEmojiReaction(null))
    }

    @Test fun emojiContentIsAnEmojiReaction() {
        assertTrue(isEmojiReaction("🔥"))
        assertTrue(isEmojiReaction("👍🏽"))
    }

    @Test fun customEmojiShortcodeIsNotDrawnAsEmoji() {
        // NIP-30 ":name:" has no glyph to draw in place of the heart.
        assertFalse(isEmojiReaction(":tbone:"))
    }
}
