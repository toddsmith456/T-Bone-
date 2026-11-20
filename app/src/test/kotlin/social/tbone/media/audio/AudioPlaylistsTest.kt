package social.tbone.media.audio

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.db.BookmarkFolderKind
import social.tbone.notes.BookmarkFolderUi
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind

class AudioPlaylistsTest {
    @Test
    fun playlistTitlesRemainOrdinaryOnlineBookmarkSets() {
        val title = AudioPlaylists.title("Road Mix")
        val playlist = onlineFolder(title)

        assertEquals("T-Bone Music / Road Mix", title)
        assertEquals("Road Mix", AudioPlaylists.displayName(title))
        assertEquals("Road Mix", AudioPlaylists.displayName("t-bone music / Road Mix"))
        assertTrue(AudioPlaylists.isPlaylist(playlist))
        assertTrue(AudioPlaylists.isPlaylist(playlist.copy(kind = BookmarkFolderKind.OFFLINE)))
    }

    @Test
    fun onlyAudioAttachmentsAreReturned() {
        val audio = "https://cdn.example/song.mp3?token=abc"
        val video = "https://cdn.example/clip.mp4"
        val note = event(
            content = "Listen $audio and watch $video",
            tags = listOf(listOf("t", "tbonemusic")),
        )

        assertEquals(listOf(audio), audioUrls(note))
    }

    @Test
    fun imetaMimeRecognizesExtensionlessAudioAndIgnoresOtherMedia() {
        val extensionless = "https://cdn.example/opaque-id"
        val image = "https://cdn.example/picture"
        val note = event(
            content = "#TboneMusic $extensionless $image",
            tags = listOf(
                listOf("imeta", "url $extensionless", "m audio/flac"),
                listOf("imeta", "url $image", "m image/jpeg"),
            ),
        )

        assertEquals(listOf(extensionless), audioUrls(note))
    }

    @Test
    fun noteProseAndPosterAreNotMusicIdentity() {
        val url = "https://cdn.example/song.mp3"
        val note = event("A totally unrelated note $url", emptyList())
        assertEquals("song", audioTrackTitle(note, url))
        assertEquals("Unknown artist", audioIdentity(note, url).artist)
    }

    @Test
    fun readsTitleArtistAndAlbumFromMatchingAttachmentOnly() {
        val url = "https://cdn.example/song.flac"
        val note = event("not a title", listOf(
            listOf("imeta", "url https://cdn.example/other.flac", "title Wrong"),
            listOf("imeta", "url $url", "m audio/flac", "title Song", "artist Artist", "album Album"),
        ))
        assertEquals(AudioIdentity("Song", "Artist", "Album"), audioIdentity(note, url))
    }

    private fun onlineFolder(name: String) = BookmarkFolderUi(
        id = "playlist-id",
        name = name,
        sortOrder = 0.0,
        createdAt = 0L,
        updatedAt = 0L,
        kind = BookmarkFolderKind.ONLINE,
        dTag = "t-bone-music-road-mix",
    )

    private fun event(content: String, tags: List<List<String>>): Event = Event(
        id = "0".repeat(64),
        pubkey = "1".repeat(64),
        createdAt = 1L,
        kind = EventKind.TEXT_NOTE,
        tags = tags.map { values -> buildJsonArray { values.forEach { add(JsonPrimitive(it)) } } },
        content = content,
        sig = "",
    )
}
