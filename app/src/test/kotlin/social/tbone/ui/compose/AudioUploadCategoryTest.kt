package social.tbone.ui.compose

import org.junit.Assert.*
import org.junit.Test

class AudioUploadCategoryTest {
    @Test fun podcastsDoNotBecomeMusic() {
        assertEquals("url\n\n#TbonePodcast", appendAudioHashtags("url", listOf("TbonePodcast")))
        assertFalse(appendAudioHashtags("url", listOf("TbonePodcast")).contains("#TboneMusic"))
    }
    @Test fun tagsAreCaseInsensitiveAndNotDuplicated() {
        assertEquals("url #tbonepodcast", appendAudioHashtags("url #tbonepodcast", listOf("TbonePodcast")))
        assertEquals("url\n\n#TboneMusic", appendAudioHashtags("url", listOf("TboneMusic", "TboneMusic")))
    }
}
