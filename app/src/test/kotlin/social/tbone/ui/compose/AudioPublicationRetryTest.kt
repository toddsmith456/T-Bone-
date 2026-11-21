package social.tbone.ui.compose

import org.junit.Assert.*
import org.junit.Test
import social.tbone.nostr.Event

class AudioPublicationRetryTest {
    private val signed = Event(id = "0".repeat(64), pubkey = "owner", createdAt = 1, kind = 1,
        tags = emptyList(), content = "url #TbonePodcast", sig = "signed")
    @Test fun retriesReuseOnlyTheSameOwnersSameContentAudioNote() {
        assertTrue(canReuseAudioSignature(true, signed, "owner", "url #TbonePodcast"))
        assertFalse(canReuseAudioSignature(true, signed, "other", "url #TbonePodcast"))
        assertFalse(canReuseAudioSignature(true, signed, "owner", "different"))
        assertFalse(canReuseAudioSignature(false, signed, "owner", "url #TbonePodcast"))
    }
}
