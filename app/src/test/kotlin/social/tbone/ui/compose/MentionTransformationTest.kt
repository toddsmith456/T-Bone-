package social.tbone.ui.compose

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent

class MentionTransformationTest {
    private val hex = "ab".repeat(32)
    private val npub = Nip19.hexToNpub(hex)

    @Test
    fun `shorter mention labels use bounds-safe offset mapping`() {
        val text = "hi @$npub and @$npub end"
        val transformed = buildMentionTransformedText(
            text = text,
            profiles = mapOf(hex to ProfileContent(name = "Alice")),
            accentColor = Color.Green,
        )

        assertEquals("hi @Alice and @Alice end", transformed.text.text)
        assertTrue(transformed.text.length < text.length)

        val originalToTransformed = (0..text.length).map {
            transformed.offsetMapping.originalToTransformed(it)
        }
        assertTrue(originalToTransformed.all { it in 0..transformed.text.length })
        assertEquals(originalToTransformed.sorted(), originalToTransformed)
        assertEquals(transformed.text.length, originalToTransformed.last())

        val transformedToOriginal = (0..transformed.text.length).map {
            transformed.offsetMapping.transformedToOriginal(it)
        }
        assertTrue(transformedToOriginal.all { it in 0..text.length })
        assertEquals(transformedToOriginal.sorted(), transformedToOriginal)
        assertEquals(text.length, transformedToOriginal.last())
    }

    @Test
    fun `out-of-range offsets are clamped instead of throwing`() {
        val text = "@$npub"
        val transformed = buildMentionTransformedText(text, emptyMap(), Color.Green)

        assertEquals(0, transformed.offsetMapping.originalToTransformed(-20))
        assertEquals(
            transformed.text.length,
            transformed.offsetMapping.originalToTransformed(text.length + 20),
        )
        assertEquals(0, transformed.offsetMapping.transformedToOriginal(-20))
        assertEquals(
            text.length,
            transformed.offsetMapping.transformedToOriginal(transformed.text.length + 20),
        )
    }

    @Test
    fun `an invalid npub-like token is left untouched`() {
        val invalid = "npub1" + "q".repeat(58)
        assertNull(Nip19.npubToHex(invalid))
        val text = "typo @$invalid"
        val transformed = buildMentionTransformedText(text, emptyMap(), Color.Green)

        assertEquals(text, transformed.text.text)
    }

    @Test
    fun `plain text keeps its exact contents`() {
        val text = "no mentions here — just text"
        val transformed = buildMentionTransformedText(text, emptyMap(), Color.Green)

        assertEquals(text, transformed.text.text)
        assertEquals(text.length, transformed.offsetMapping.originalToTransformed(text.length))
    }
}
