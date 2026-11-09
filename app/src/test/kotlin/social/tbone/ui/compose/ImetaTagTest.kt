package social.tbone.ui.compose

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ImetaTagTest {

    private fun entries(hint: UploadedMediaHint): List<String> =
        buildImetaTag(hint).map { (it as JsonPrimitive).content }

    @Test
    fun `full hint produces url mime dim and blurhash`() {
        val tag = entries(
            UploadedMediaHint(
                url = "https://blossom.example/cat.jpg",
                mime = "image/jpeg",
                width = 1200,
                height = 900,
                blurhash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
            ),
        )
        assertEquals(
            listOf(
                "imeta",
                "url https://blossom.example/cat.jpg",
                "m image/jpeg",
                "dim 1200x900",
                "blurhash LEHV6nWB2yk8pyo0adR*.7kCMdnj",
            ),
            tag,
        )
    }

    @Test
    fun `missing dimensions and blurhash are omitted`() {
        val tag = entries(
            UploadedMediaHint(url = "https://blossom.example/clip.mp4", mime = "video/mp4"),
        )
        assertEquals(
            listOf("imeta", "url https://blossom.example/clip.mp4", "m video/mp4"),
            tag,
        )
    }
}
