package social.tbone.ui.feed

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.nostr.Event

class MediaMetaParseTest {

    private fun tag(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun event(content: String, tags: List<JsonArray>) = Event(
        id = "a".repeat(64),
        pubkey = "b".repeat(64),
        createdAt = 1L,
        kind = 1,
        tags = tags,
        content = content,
        sig = "c".repeat(128),
    )

    @Test
    fun `imeta dimensions and blurhash reach the image item`() {
        val event = event(
            content = "look https://cdn.example/cat.jpg",
            tags = listOf(
                tag(
                    "imeta",
                    "url https://cdn.example/cat.jpg",
                    "m image/jpeg",
                    "dim 1200x900",
                    "blurhash LEHV6nWB2yk8pyo0adR*.7kCMdnj",
                ),
            ),
        )
        val parsed = parseEventContent(event)
        val image = parsed.mediaItems.single() as MediaItem.Image
        assertEquals("1200x900", image.meta?.dimension)
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", image.meta?.blurhash)
        assertEquals(1200f / 900f, image.meta?.aspectRatio ?: -1f, 0.001f)
    }

    @Test
    fun `imeta poster reaches the video item`() {
        val event = event(
            content = "watch https://cdn.example/clip.mp4",
            tags = listOf(
                tag(
                    "imeta",
                    "url https://cdn.example/clip.mp4",
                    "m video/mp4",
                    "dim 1920x1080",
                    "image https://cdn.example/clip-poster.jpg",
                ),
            ),
        )
        val video = parseEventContent(event).mediaItems.single() as MediaItem.Video
        assertEquals("https://cdn.example/clip-poster.jpg", video.meta?.image)
        assertEquals(16f / 9f, video.meta?.aspectRatio ?: -1f, 0.001f)
    }

    @Test
    fun `media without imeta has no hints`() {
        val event = event(content = "plain https://cdn.example/cat.jpg", tags = emptyList())
        val image = parseEventContent(event).mediaItems.single() as MediaItem.Image
        assertTrue(image.meta == null || image.meta?.dimension == null)
        assertNull(parseMediaAspectRatio(null))
        assertNull(parseMediaAspectRatio("0x900"))
        assertNull(parseMediaAspectRatio("big"))
    }
}
