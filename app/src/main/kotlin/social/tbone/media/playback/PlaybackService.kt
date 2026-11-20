package social.tbone.media.playback

import androidx.annotation.OptIn
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import social.tbone.settings.AppSettings
import javax.inject.Inject
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import social.tbone.cache.MediaCacheManager
import timber.log.Timber

/**
 * Owns the app's single background-capable player and media session.
 *
 * The UI connects through a MediaController. Keeping ownership here means a
 * full-screen viewer can detach while its audio continues, and the same queue
 * can be controlled from the Audio Player, notification and lock screen.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    @Inject lateinit var appSettings: AppSettings

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsJob: Job? = null
    private var backgroundPlaybackAllowed = true
    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var cacheLeaseHeld = false

    private val processObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            player?.setVideoTrackEnabled(false)
            if (!backgroundPlaybackAllowed) player?.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        settingsJob = serviceScope.launch {
            appSettings.mediaBackgroundPlayback.collect { allowed ->
                backgroundPlaybackAllowed = allowed
                if (!allowed && !ProcessLifecycleOwner.get().lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                ) {
                    player?.pause()
                }
            }
        }
        runCatching {
            val dataSourceFactory = MediaCacheManager.acquireFactory()
            cacheLeaseHeld = true
            val created = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        /* handleAudioFocus = */ true,
                    )
                    repeatMode = Player.REPEAT_MODE_OFF
                }
            player = created
            mediaSession = MediaSession.Builder(this, created).build()
        }.onFailure { error ->
            Timber.e(error, "Could not initialize the background media session")
            releasePlayback()
            stopSelf()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        settingsJob?.cancel()
        serviceScope.cancel()
        releasePlayback()
        super.onDestroy()
    }

    private fun releasePlayback() {
        runCatching { mediaSession?.release() }
        mediaSession = null
        runCatching { player?.release() }
        player = null
        if (cacheLeaseHeld) {
            cacheLeaseHeld = false
            MediaCacheManager.releaseLease()
        }
    }
}
