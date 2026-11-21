package social.tbone.ui.media

import androidx.compose.material3.MaterialTheme
import social.tbone.ui.theme.appCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import social.tbone.di.ImageClientProvider
import social.tbone.media.MimeTypes
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

val LocalOpenAudioPlayer = androidx.compose.runtime.staticCompositionLocalOf<(String, String?, String?) -> Unit> { { _, _, _ -> } }

/**
 * A compact, tap-to-load audio card. The URL is used only as the player source;
 * the visible card shows a generic title and format label, never the Blossom URL.
 */
@Composable
fun InlineAudioPlayer(
    url: String,
    authorPubkey: String? = null,
    sourceEventId: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val openPlayer = LocalOpenAudioPlayer.current
    val activeUrl by social.tbone.media.playback.AudioSessionActivity.url.collectAsState()
    var requested by remember(url) { mutableStateOf(false) }
    var pendingAction by remember(url) { mutableStateOf<String?>(null) }
    var retryConnection by remember { mutableStateOf(0) }
    val connection = social.tbone.media.playback.rememberPlaybackConnection(requested || activeUrl == url, retryConnection)
    val player = connection.controller
    val state = social.tbone.media.playback.rememberAudioPlaybackState(player, connection.error)
    val selected = state.urlKey == social.tbone.media.audio.AudioCachePolicy.key(url)
    LaunchedEffect(player, pendingAction) {
        val action = pendingAction ?: return@LaunchedEffect
        if (player != null) {
            val alreadySelected = social.tbone.media.playback.isAudioSelected(player, url)
            social.tbone.media.playback.selectInlineAudio(player,
                social.tbone.media.playback.AudioTrackIdentity(url, sourceEventId.orEmpty(), authorPubkey.orEmpty()), autoplay = true)
            pendingAction = null
            if (action == "expand") openPlayer(url, authorPubkey, sourceEventId)
            else if (alreadySelected) social.tbone.media.playback.toggleAudio(player)
        }
    }
    AudioPlayerCard(
        format = audioFormatLabel(url),
        title = if (selected) state.title else social.tbone.media.playback.audioFilename(url),
        isPlaying = selected && state.isPlaying,
        isBuffering = (selected && state.isBuffering) || (pendingAction != null && connection.error == null),
        positionMs = if (selected) state.positionMs else 0,
        durationMs = if (selected) state.durationMs else 0,
        error = connection.error != null || (selected && state.error != null),
        onPlayPause = {
            requested = true
            if (connection.error != null) retryConnection++
            pendingAction = "play"
        },
        onSeek = { if (selected && state.durationMs > 0) player?.seekTo((state.durationMs * it).toLong()) },
        onCopyLink = { social.tbone.ui.feed.copyNoteLink(context, url) },
        onOpenPlayer = {
            requested = true
            if (connection.error != null) retryConnection++
            pendingAction = "expand"
        },
        modifier = modifier,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioPlayerCard(
    format: String,
    title: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    error: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onCopyLink: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = appCornerShape(MaterialTheme.shapes.small, RoundedCornerShape(12.dp))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BonyColors.SurfaceAlt, shape)
            .border(1.dp, BonyColors.Rule, shape)
            .combinedClickable(onClick = {}, onLongClick = onCopyLink)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onOpenPlayer) {
                Text("↗", color = BonyColors.Accent, modifier = Modifier.semantics { contentDescription = "Open in audio player" })
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = if (isPlaying) "Pause audio" else if (error) "Retry audio" else "Play audio" }
                    .clip(CircleShape)
                    .background(BonyColors.AccentBg)
                    .clickable(role = Role.Button, onClick = onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        isBuffering -> "…"
                        isPlaying -> "Ⅱ"
                        else -> "▶"
                    },
                    style = BonyType.body.copy(color = BonyColors.Accent),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (error) "audio couldn't be played" else title.ifBlank { "audio · $format" },
                    style = BonyType.tag.copy(color = if (error) BonyColors.Danger else BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isBuffering -> "buffering…"
                        durationMs > 0L -> "${formatAudioTime(positionMs)} / ${formatAudioTime(durationMs)}"
                        error -> "tap to retry"
                        else -> "tap to play"
                    },
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                )
            }
        }

        if (durationMs > 0L) {
            val progress = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Slider(
                value = progress,
                onValueChange = onSeek,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BonyColors.Accent,
                    activeTrackColor = BonyColors.Accent,
                    inactiveTrackColor = BonyColors.RuleStrong,
                ),
            )
        }
    }
}

private fun audioFormatLabel(url: String): String = when (
    url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()
) {
    "mp3", "mpga", "mpeg3", "mpg3" -> "MP3"
    "m4a", "m4b", "m4p" -> "M4A"
    "aac" -> "AAC"
    "wav" -> "WAV"
    "flac" -> "FLAC"
    "ogg", "oga" -> "OGG"
    "opus" -> "OPUS"
    "weba", "webm" -> "WEBM"
    "aif", "aiff" -> "AIFF"
    "amr" -> "AMR"
    "mka" -> "MKA"
    else -> "AUDIO"
}

private fun formatAudioTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

/** Backgrounding pauses playback; resuming never automatically restarts it. */
internal fun pauseAudioOnStop(lifecycle: Lifecycle, pause: () -> Unit): () -> Unit {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) pause()
    }
    lifecycle.addObserver(observer)
    return { lifecycle.removeObserver(observer) }
}
