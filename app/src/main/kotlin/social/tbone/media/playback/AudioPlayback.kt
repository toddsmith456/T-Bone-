package social.tbone.media.playback

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import social.tbone.media.MimeTypes

/** A process-local hint only. The MediaSession remains the authority for position and queue. */
object AudioSessionActivity {
    private val _url = MutableStateFlow<String?>(null)
    val url: StateFlow<String?> = _url
    fun update(url: String?) { _url.value = url }
}

enum class AudioPhase { CONNECTING, IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

fun audioPhase(connected: Boolean, failed: Boolean, hasItem: Boolean, state: Int, playing: Boolean): AudioPhase = when {
    failed -> AudioPhase.ERROR
    !connected -> AudioPhase.CONNECTING
    !hasItem -> AudioPhase.IDLE
    state == Player.STATE_BUFFERING -> AudioPhase.PREPARING
    state == Player.STATE_ENDED -> AudioPhase.ENDED
    playing -> AudioPhase.PLAYING
    state == Player.STATE_IDLE -> AudioPhase.IDLE
    else -> AudioPhase.PAUSED
}

fun audioFilename(url: String): String = runCatching {
    java.net.URLDecoder.decode(url.substringBefore('?').substringBefore('#').substringAfterLast('/').replace("+", "%2B"), "UTF-8")
        .substringBeforeLast('.').ifBlank { "Untitled track" }
}.getOrDefault("Untitled track")

fun stableAudioId(url: String, eventId: String = ""): String =
    "audio:$eventId:${social.tbone.media.audio.AudioCachePolicy.key(url)}"

/** Provenance never masquerades as artist identity. All queue entry points use this contract. */
data class AudioTrackIdentity(
    val url: String,
    val eventId: String = "",
    val poster: String = "",
    val origin: String = "Inline audio",
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val artwork: ByteArray? = null,
) {
    fun mediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
            .setArtworkUri(artworkUri?.let(Uri::parse)).setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .setExtras(Bundle().apply {
                putString("audioUrlKey", social.tbone.media.audio.AudioCachePolicy.key(url)); putBoolean("audioTrack", true); putString("eventId", eventId); putString("poster", poster); putString("queueOrigin", origin)
                putString("fallbackTitle", title ?: audioFilename(url))
            }).build()
        val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        val mime = MimeTypes.mimeForExtension(extension)?.takeIf(MimeTypes::isAudio)
        return MediaItem.Builder().setMediaId(stableAudioId(url, eventId)).setUri(url).setMimeType(mime)
            .setMediaMetadata(metadata).build()
    }
}

data class AudioQueueEntry(val id: String, val title: String, val artist: String, val index: Int)
data class AudioPlaybackState(
    val phase: AudioPhase = AudioPhase.CONNECTING,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: ByteArray? = null,
    val artworkUri: String? = null,
    val poster: String = "",
    val eventId: String = "",
    val origin: String = "",
    val urlKey: String? = null,
    val error: String? = null,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val index: Int = -1,
    val queue: List<AudioQueueEntry> = emptyList(),
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

@Composable
fun rememberAudioPlaybackState(controller: MediaController?, connectionError: String? = null): AudioPlaybackState {
    var snapshot by remember(controller) { mutableStateOf(AudioPlaybackState()) }
    fun read() {
        val p = controller
        if (p == null) { snapshot = AudioPlaybackState(phase = if (connectionError == null) AudioPhase.CONNECTING else AudioPhase.ERROR, error = connectionError); return }
        if (!p.isConnected) { snapshot = AudioPlaybackState(phase = AudioPhase.ERROR, error = "Playback disconnected. Reconnect to continue."); return }
        val item = p.currentMediaItem
        val metadata = p.mediaMetadata
        val extras = item?.mediaMetadata?.extras
        snapshot = AudioPlaybackState(
            phase = audioPhase(true, p.playerError != null, item != null, p.playbackState, p.isPlaying),
            isPlaying = p.isPlaying, isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0), durationMs = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0,
            title = metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: extras?.getString("fallbackTitle").orEmpty(),
            artist = metadata.artist?.toString().orEmpty(), album = metadata.albumTitle?.toString().orEmpty(),
            artwork = metadata.artworkData, artworkUri = metadata.artworkUri?.toString(),
            poster = extras?.getString("poster").orEmpty(), eventId = extras?.getString("eventId").orEmpty(), origin = extras?.getString("queueOrigin").orEmpty(),
            urlKey = extras?.getString("audioUrlKey") ?: item?.localConfiguration?.uri?.toString()?.let(social.tbone.media.audio.AudioCachePolicy::key),
            error = p.playerError?.let { "Audio could not be played. Check your connection or try again. (${it.errorCodeName})" },
            hasPrevious = p.hasPreviousMediaItem(), hasNext = p.hasNextMediaItem(), index = p.currentMediaItemIndex,
            queue = (0 until p.mediaItemCount).map { index ->
                val entry = p.getMediaItemAt(index)
                AudioQueueEntry(entry.mediaId, (if (index == p.currentMediaItemIndex) metadata.title?.toString() else null) ?: entry.mediaMetadata.title?.toString() ?: entry.mediaMetadata.extras?.getString("fallbackTitle") ?: "Untitled track",
                    entry.mediaMetadata.artist?.toString().orEmpty(), index)
            }, shuffle = p.shuffleModeEnabled, repeatMode = p.repeatMode,
        )
    }
    DisposableEffect(controller, connectionError) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { read() }
        }
        controller?.addListener(listener)
        read()
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller, connectionError) {
        while (true) { read(); delay(500) }
    }
    return snapshot
}

/** A handoff to an already selected URI is a no-op: never reset position, queue or pause. */
fun selectInlineAudio(player: Player, track: AudioTrackIdentity, autoplay: Boolean) {
    if (isAudioSelected(player, track.url)) return
    player.setMediaItem(track.mediaItem())
    player.prepare()
    player.playWhenReady = autoplay
}

fun retryAudio(player: Player) {
    if (player.currentMediaItem == null) return
    // prepare retries the existing item at its current position without destroying its queue.
    player.prepare()
    player.play()
}
fun toggleAudio(player: Player) {
    when {
        player.playerError != null -> retryAudio(player)
        player.isPlaying -> player.pause()
        player.playbackState == Player.STATE_ENDED -> { player.seekToDefaultPosition(); player.play() }
        else -> { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
    }
}

/** Only actual nonblank enrichment triggers a session metadata update. */
fun audioDetailsChanged(oldTitle: String?, oldArtist: String?, oldAlbum: String?, oldArt: ByteArray?,
    title: String?, artist: String?, album: String?, art: ByteArray?): Boolean =
    (title?.takeIf(String::isNotBlank)?.let { it != oldTitle } == true) ||
        (artist?.takeIf(String::isNotBlank)?.let { it != oldArtist } == true) ||
        (album?.takeIf(String::isNotBlank)?.let { it != oldAlbum } == true) ||
        (art != null && !art.contentEquals(oldArt))

fun preserveAudioSelection(currentUrl: String?, requestedUrl: String, currentUrlKey: String? = null): Boolean =
    (currentUrl != null && currentUrl == requestedUrl) ||
        (currentUrlKey != null && currentUrlKey == social.tbone.media.audio.AudioCachePolicy.key(requestedUrl))

/** Session timeline transport strips LocalConfiguration. Compare the safe URL hash in extras instead. */
fun isAudioSelected(player: Player, url: String): Boolean = preserveAudioSelection(
    player.currentMediaItem?.localConfiguration?.uri?.toString(), url,
    player.currentMediaItem?.mediaMetadata?.extras?.getString("audioUrlKey"),
)

fun audioQueueMoveTarget(index: Int, count: Int, delta: Int): Int? =
    (index + delta).takeIf { index in 0 until count && it in 0 until count }
