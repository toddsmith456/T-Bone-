package social.tbone.ui.audio

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.*
import org.junit.Test
import social.tbone.nostr.Event

class AudioDiscoveryTest {
    private fun note(tag: String, author: String = "alice", url: String = "https://example.org/track.mp3") = Event(
        id = "0".repeat(64), pubkey = author, createdAt = 1, kind = 1,
        tags = listOf(buildJsonArray { add(JsonPrimitive("t")); add(JsonPrimitive(tag)) }), content = url, sig = "",
    )
    @Test fun podcastAndMusicAreSeparate() {
        assertTrue(matchesAudioFeed(note("TbonePodcast"), AudioCategory.PODCASTS, AudioAudience.GLOBAL, emptySet()))
        assertFalse(matchesAudioFeed(note("TbonePodcast"), AudioCategory.MUSIC, AudioAudience.GLOBAL, emptySet()))
    }
    @Test fun followsCannotLeakGlobalPosts() {
        assertFalse(matchesAudioFeed(note("tbonemusic"), AudioCategory.MUSIC, AudioAudience.FOLLOWS, emptySet()))
        assertFalse(matchesAudioFeed(note("tbonemusic", "bob"), AudioCategory.MUSIC, AudioAudience.FOLLOWS, setOf("alice")))
        assertTrue(matchesAudioFeed(note("tbonemusic"), AudioCategory.MUSIC, AudioAudience.FOLLOWS, setOf("alice")))
    }
    @Test fun offlineFollowModeIsAuthoritative() {
        assertEquals(setOf("local"), audioFollowSet(setOf("relay"), true, setOf("local")))
        assertEquals(emptySet<String>(), audioFollowSet(setOf("relay"), true, emptySet()))
        assertEquals(setOf("relay"), audioFollowSet(setOf("relay"), false, setOf("local")))
    }
    @Test fun hashtagWithoutAudioIsNotATrack() {
        assertFalse(matchesAudioFeed(note("tbonemusic", url = "hello"), AudioCategory.MUSIC, AudioAudience.GLOBAL, emptySet()))
    }
}
