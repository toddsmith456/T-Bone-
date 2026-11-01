package social.tbone.ui.media

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import social.tbone.di.ImageClientProvider
import social.tbone.media.MimeTypes
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * A compact, tap-to-load audio card. The URL is used only as the player source;
 * the visible card shows a generic title and format label, never the Blossom URL.
 */
@Composable
fun InlineAudioPlayer(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val format = remember(url) { audioFormatLabel(url) }
    var playerRequested by remember(url) { mutableStateOf(false) }
    var playWhenReady by remember(url) { mutableStateOf(false) }

    if (!playerRequested) {
        AudioPlayerCard(
            format = format,
            isPlaying = false,
            isBuffering = false,
            positionMs = 0L,
            durationMs = 0L,
            error = false,
            onPlayPause = {
                playWhenReady = true
                playerRequested = true
            },
            onSeek = {},
            onCopyLink = { social.tbone.ui.feed.copyNoteLink(context, url) },
            modifier = modifier,
        )
    } else {
        val player = rememberAudioPlayer(url)
        var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
        var isBuffering by remember(player) {
            mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
        }
        var positionMs by remember(player) { mutableStateOf(0L) }
        var durationMs by remember(player) { mutableStateOf(0L) }
        var hasError by remember(player) { mutableStateOf(false) }

        DisposableEffect(player) {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                    isPlaying = isPlayingNow
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    isBuffering = playbackState == Player.STATE_BUFFERING
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    if (playbackState == Player.STATE_READY) hasError = false
                }

                override fun onPlayerError(error: PlaybackException) {
                    hasError = true
                    isPlaying = false
                    isBuffering = false
                }
            }
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }

        LaunchedEffect(player) {
            while (true) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                delay(250)
            }
        }

        LaunchedEffect(player, playWhenReady) {
            if (playWhenReady) {
                player.play()
                playWhenReady = false
            }
        }

        AudioPlayerCard(
            format = format,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = positionMs,
            durationMs = durationMs,
            error = hasError,
            onPlayPause = {
                when {
                    hasError -> {
                        player.seekTo(0L)
                        player.prepare()
                        player.play()
                    }
                    player.isPlaying -> player.pause()
                    player.playbackState == Player.STATE_ENDED -> {
                        player.seekTo(0L)
                        player.play()
                    }
                    else -> player.play()
                }
            },
            onSeek = { fraction ->
                if (durationMs > 0L) {
                    player.seekTo((durationMs * fraction.coerceIn(0f, 1f)).toLong())
                }
            },
            onCopyLink = { social.tbone.ui.feed.copyNoteLink(context, url) },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AudioPlayerCard(
    format: String,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    error: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onCopyLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
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
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(BonyColors.AccentBg)
                    .clickable(onClick = onPlayPause),
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
                    text = if (error) "audio couldn't be played" else "audio · $format",
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

@Composable
private fun rememberAudioPlayer(url: String): ExoPlayer {
    val context = LocalContext.current
    val player = remember(url) {
        val dataSourceFactory = OkHttpDataSource.Factory(ImageClientProvider.client)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .also { created ->
                created.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                created.repeatMode = Player.REPEAT_MODE_OFF
                created.setMediaItem(audioMediaItem(url))
                created.prepare()
            }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    return player
}

/** Normalize filename MIME aliases (notably `.mpga`) to Media3's canonical audio MIME. */
private fun audioMediaItem(url: String): MediaItem {
    val path = url.substringBefore('?').substringBefore('#')
    val extension = path.substringAfterLast('/', "").substringAfterLast('.', "").lowercase()
    val mime = MimeTypes.mimeForExtension(extension)?.takeIf { MimeTypes.isAudio(it) }
    val builder = MediaItem.Builder().setUri(url)
    if (mime != null) builder.setMimeType(mime)
    return builder.build()
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
