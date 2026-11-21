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
import kotlinx.coroutines.flow.first
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
    @Inject lateinit var progressRepository: PodcastProgressRepository
    @Inject lateinit var accounts: social.tbone.account.AccountRepository
    @Inject lateinit var metadataCache: social.tbone.media.audio.AudioMetadataCache
    @Inject lateinit var appSettings: AppSettings

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var metadataJob: Job? = null
    private data class Episode(val owner: String, val key: String, val epoch: Long, var progress: PodcastProgress)
    private var episode: Episode? = null
    private var progressJob: Job? = null
    private var resumeJob: Job? = null
    private var episodeToken = 0L
    private var userSoughtDuringResume = false
    private var lastEpisodeUrl: String? = null
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
            created.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    captureProgress()
                    if (isPlaying) beginEpisodePlayback() else saveProgress()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    captureProgress()
                    if (playbackState == Player.STATE_ENDED) saveProgress(completed = true)
                }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) userSoughtDuringResume = true
                    val active = episode
                    val oldKey = oldPosition.mediaItem?.mediaMetadata?.extras?.getString("audioUrlKey")
                    if (active != null && active.key == oldKey) {
                        val sameEpisode = newPosition.mediaItem?.mediaMetadata?.extras?.getString("audioUrlKey") == active.key
                        active.progress = active.progress.copy(position = if (sameEpisode) newPosition.positionMs else oldPosition.positionMs)
                        saveProgress(completed = !sameEpisode && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    }
                }
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    val url = mediaItem?.localConfiguration?.uri?.toString()
                    val audioTrack = mediaItem?.mediaMetadata?.extras?.getBoolean("audioTrack") == true
                    val episodeIdentity = url?.let { it + ":" + (mediaItem?.mediaMetadata?.extras?.getBoolean("isPodcast") == true) }
                    if (episodeIdentity != lastEpisodeUrl) {
                        saveProgress()
                        episode = null
                        lastEpisodeUrl = episodeIdentity
                        userSoughtDuringResume = false
                        episodeToken++
                        val token = episodeToken
                        resumeJob?.cancel()
                        created.setPlaybackSpeed(1f) // podcast speed must never leak into music/video
                        if (audioTrack && mediaItem?.mediaMetadata?.extras?.getBoolean("isPodcast") == true && url != null) {
                            resumeJob = serviceScope.launch {
                                val owner = accounts.activeAccount.first()?.pubkey ?: return@launch
                                val key = social.tbone.media.audio.AudioCachePolicy.key(url)
                                val epoch = progressRepository.epoch()
                                val saved = progressRepository.read(owner, key)
                                if (episodeToken != token) return@launch
                                episode = Episode(owner, key, epoch, saved)
                                created.setPlaybackSpeed(saved.speed)
                                if (!userSoughtDuringResume && created.currentPosition < 2_000) {
                                    val position = podcastResumePosition(saved)
                                    if (position > 0) created.seekTo(position)
                                }
                                if (created.isPlaying) beginEpisodePlayback()
                            }
                        }
                    }
                    AudioSessionActivity.update(url.takeIf { audioTrack })
                    metadataJob?.cancel()
                    if (url == null || mediaItem == null || !audioTrack) return
                    val id = mediaItem.mediaId
                    metadataJob = serviceScope.launch {
                        // Playback preparation never waits for these optional details.
                        metadataCache.read(url, allowNetwork = false)?.let { applyDetails(created, id, url, it) }
                        metadataCache.read(url)?.let { applyDetails(created, id, url, it) }
                    }
                }
            })
            progressJob = serviceScope.launch {
                while (true) { kotlinx.coroutines.delay(5_000); captureProgress(); saveProgress() }
            }
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
        captureProgress()
        saveProgress()
        progressJob?.cancel()
        resumeJob?.cancel()
        settingsJob?.cancel()
        metadataJob?.cancel()
        AudioSessionActivity.update(null)
        serviceScope.cancel()
        releasePlayback()
        super.onDestroy()
    }

    private fun captureProgress() {
        val p = player ?: return
        val current = episode ?: return
        if (p.currentMediaItem?.mediaMetadata?.extras?.getString("audioUrlKey") != current.key) return
        current.progress = current.progress.copy(position = p.currentPosition.coerceAtLeast(0),
            duration = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: current.progress.duration, speed = p.playbackParameters.speed)
    }
    private fun beginEpisodePlayback() {
        val current = episode ?: return
        if (current.progress.played) {
            current.progress = current.progress.copy(played = false)
            saveProgress(playedOverride = false)
        }
    }
    private fun saveProgress(completed: Boolean = false, playedOverride: Boolean? = null) {
        val current = episode ?: return
        if (completed) current.progress = current.progress.copy(played = true)
        val snapshot = current.progress
        val recordedAt = progressRepository.timestamp()
        // Repository serializes through the existing wipe-aware Notes writer, even on service shutdown.
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            runCatching { progressRepository.save(current.owner, current.key, snapshot, if (completed) true else playedOverride, recordedAt, current.epoch) }
        }
    }

    private fun applyDetails(
        target: ExoPlayer, id: String, url: String,
        details: social.tbone.media.audio.AudioMetadataReader.Metadata,
    ) {
        val current = target.currentMediaItem ?: return
        if (current.mediaId != id || current.localConfiguration?.uri?.toString() != url) return
        val old = current.mediaMetadata
        if (!audioDetailsChanged(old.title?.toString(), old.artist?.toString(), old.albumTitle?.toString(), old.artworkData,
                details.title, details.artist, details.album, details.artwork)) return
        val metadata = old.buildUpon().apply {
            details.title?.takeIf { it.isNotBlank() }?.let { setTitle(it) }
            details.artist?.takeIf { it.isNotBlank() }?.let { setArtist(it) }
            details.album?.takeIf { it.isNotBlank() }?.let { setAlbumTitle(it) }
            details.artwork?.let { setArtworkData(it, androidx.media3.common.MediaMetadata.PICTURE_TYPE_FRONT_COVER) }
        }.build()
        // Same URI/media source: Media3 updates metadata without preparing/seeking/rebuilding the queue.
        target.replaceMediaItem(target.currentMediaItemIndex, current.buildUpon().setMediaMetadata(metadata).build())
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
