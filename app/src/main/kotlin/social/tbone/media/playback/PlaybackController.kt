package social.tbone.media.playback

import android.content.ComponentName
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import social.tbone.settings.VideoQuality

data class PlaybackConnection(val controller: MediaController? = null, val error: String? = null)

/** Connections may be recreated without owning or resetting the service queue. */
@Composable
fun rememberPlaybackConnection(enabled: Boolean = true, retry: Int = 0): PlaybackConnection {
    if (!enabled) return PlaybackConnection()
    val context = LocalContext.current.applicationContext
    var disconnected by remember(context, retry) { mutableStateOf(false) }
    val future = remember(context, retry) {
        MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) { disconnected = true }
            }).buildAsync()
    }
    var connection by remember(future) { mutableStateOf(PlaybackConnection()) }
    DisposableEffect(future) {
        var disposed = false
        future.addListener({
            if (!disposed) {
                connection = runCatching { PlaybackConnection(controller = future.get()) }
                    .getOrElse { PlaybackConnection(error = "Could not connect to audio playback. Tap Retry.") }
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; MediaController.releaseFuture(future) }
    }
    androidx.compose.runtime.LaunchedEffect(future) {
        kotlinx.coroutines.delay(15_000)
        if (connection.controller == null) connection = PlaybackConnection(error = "Playback service did not respond. Tap Retry.")
    }
    return if (disconnected) PlaybackConnection(error = "Playback service disconnected. Tap Retry.") else connection
}

@Composable
fun rememberPlaybackController(enabled: Boolean = true): MediaController? = rememberPlaybackConnection(enabled).controller

/** Keep the audio renderer active while a video is detached in the background. */
@OptIn(UnstableApi::class)
fun Player.setVideoTrackEnabled(enabled: Boolean) {
    val updated = trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled)
        .build()
    trackSelectionParameters = updated
}

/** Apply the same resolution preference when the fullscreen viewer uses the shared player. */
@OptIn(UnstableApi::class)
fun Player.setVideoQuality(quality: VideoQuality) {
    val builder = trackSelectionParameters.buildUpon()
    if (quality == VideoQuality.LOW) {
        builder.setMaxVideoSize(640, 360)
    } else {
        builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
    }
    trackSelectionParameters = builder.build()
}
