package social.tbone.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypesTest {

    @Test
    fun `maps the types this app produces and accepts`() {
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg"))
        assertEquals("mp4", MimeTypes.extensionFor("video/mp4"))
        assertEquals("webm", MimeTypes.extensionFor("video/webm"))
        assertEquals("heic", MimeTypes.extensionFor("image/heic"))
    }

    @Test
    fun `tolerates parameters, casing and whitespace`() {
        assertEquals("jpg", MimeTypes.extensionFor("IMAGE/JPEG"))
        assertEquals("jpg", MimeTypes.extensionFor("image/jpeg; charset=binary"))
        assertEquals("png", MimeTypes.extensionFor("  image/png  "))
    }

    @Test
    fun `unknown types yield an empty extension so the bare hash url is used`() {
        assertEquals("", MimeTypes.extensionFor("application/x-weird"))
        assertEquals("", MimeTypes.extensionFor(""))
    }

    @Test
    fun `detects video types`() {
        assertTrue(MimeTypes.isVideo("video/mp4"))
        assertFalse(MimeTypes.isVideo("image/gif"))
    }
}
