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

/** Connects a Compose surface to the single [PlaybackService] session. */
@Composable
fun rememberPlaybackController(enabled: Boolean = true): MediaController? {
    if (!enabled) return null
    val context = LocalContext.current.applicationContext
    val future = remember(context) {
        MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
    }
    var controller by remember(future) { mutableStateOf<MediaController?>(null) }

    DisposableEffect(future) {
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()
            },
            MoreExecutors.directExecutor(),
        )
        onDispose { MediaController.releaseFuture(future) }
    }
    return controller
}

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
