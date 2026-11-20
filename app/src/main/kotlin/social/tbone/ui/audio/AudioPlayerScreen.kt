package social.tbone.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay
import social.tbone.media.audio.AudioPlaylists
import social.tbone.media.audio.audioTrackTitle
import social.tbone.media.audio.audioUrl
import social.tbone.media.playback.rememberPlaybackController
import social.tbone.ui.media.LocalMediaBackgroundPlayback
import social.tbone.notes.BookmarkFolderUi
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class AudioTrack(
    val event: Event,
    val url: String,
    val title: String,
    val artist: String,
)

private data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

@Composable
fun AudioPlayerScreen(
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit,
    onProfileClick: (pubkey: String) -> Unit,
    viewModel: AudioPlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val backgroundPlaybackEnabled = LocalMediaBackgroundPlayback.current
    val controller = rememberPlaybackController()
    var activeTab by rememberSaveable { mutableStateOf(AudioPlayerTab.PLAYLISTS) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var snapshot by remember(controller) { mutableStateOf(PlaybackSnapshot()) }

    fun readPlayback(p: MediaController?) {
        if (p == null) {
            snapshot = PlaybackSnapshot()
            return
        }
        val item = p.currentMediaItem
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        snapshot = PlaybackSnapshot(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            title = item?.mediaMetadata?.title?.toString().orEmpty(),
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            hasPrevious = p.hasPreviousMediaItem(),
            hasNext = p.hasNextMediaItem(),
        )
    }

    DisposableEffect(controller, backgroundPlaybackEnabled) {
        onDispose {
            if (!backgroundPlaybackEnabled) controller?.pause()
        }
    }
    DisposableEffect(controller) {
        val listener = if (controller == null) null else object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = readPlayback(controller)
            override fun onPlayerError(error: PlaybackException) = readPlayback(controller)
        }
        listener?.let { controller?.addListener(it) }
        readPlayback(controller)
        onDispose { if (listener != null) controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            readPlayback(controller)
            delay(350)
        }
    }
    LaunchedEffect(uiState.statusMessage) {
        if (uiState.statusMessage != null) {
            delay(5_000)
            viewModel.clearStatus()
        }
    }

    val selectedPlaylist = uiState.playlists.firstOrNull { it.id == uiState.selectedPlaylistId }
    val playlistTracks = remember(uiState.playlistTracks, profiles) {
        uiState.playlistTracks.mapNotNull { bookmark -> bookmark.event.toAudioTrack(profiles) }
    }
    val discoveredTracks = remember(uiState.discoveredEvents, profiles) {
        uiState.discoveredEvents.mapNotNull { it.toAudioTrack(profiles) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BonyColors.TextMute)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("audio player", style = BonyType.title.copy(color = BonyColors.Text))
                Text(
                    text = if (selectedPlaylist != null && activeTab == AudioPlayerTab.PLAYLISTS) {
                        AudioPlaylists.displayName(selectedPlaylist.name)
                    } else "NIP-51 playlists · #TboneMusic",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    if (activeTab == AudioPlayerTab.DISCOVER) viewModel.refreshMusicFeed()
                    else viewModel.refreshPlaylists()
                },
            ) {
                if (uiState.isSyncingPlaylists && activeTab == AudioPlayerTab.PLAYLISTS) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = BonyColors.Accent)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = BonyColors.TextMute)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerTabChip(
                label = "playlists",
                selected = activeTab == AudioPlayerTab.PLAYLISTS,
                onClick = { activeTab = AudioPlayerTab.PLAYLISTS },
                modifier = Modifier.weight(1f),
            )
            PlayerTabChip(
                label = "#TboneMusic",
                selected = activeTab == AudioPlayerTab.DISCOVER,
                onClick = { activeTab = AudioPlayerTab.DISCOVER },
                modifier = Modifier.weight(1f),
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                AudioPlayerTab.PLAYLISTS -> {
                    if (selectedPlaylist == null) {
                        PlaylistsHome(
                            playlists = uiState.playlists,
                            isSyncing = uiState.isSyncingPlaylists,
                            onCreate = { showCreatePlaylist = true },
                            onSelect = viewModel::selectPlaylist,
                        )
                    } else {
                        PlaylistDetails(
                            playlist = selectedPlaylist,
                            tracks = playlistTracks,
                            profiles = profiles,
                            isConnecting = controller == null,
                            onBack = { viewModel.selectPlaylist(null) },
                            onPlayAll = { playQueue(controller, playlistTracks, 0) },
                            onPlayTrack = { index -> playQueue(controller, playlistTracks, index) },
                            onOpenNote = { onThreadClick(it.id) },
                            onOpenProfile = onProfileClick,
                            onRemove = viewModel::removeEventFromPlaylist,
                        )
                    }
                }
                AudioPlayerTab.DISCOVER -> {
                    DiscoverFeed(
                        events = uiState.discoveredEvents,
                        tracks = discoveredTracks,
                        profiles = profiles,
                        playlists = uiState.playlists,
                        loading = uiState.isLoadingFeed,
                        isConnecting = controller == null,
                        onPlayTrack = { track -> playQueue(controller, listOf(track), 0) },
                        onAddToPlaylist = viewModel::addEventToPlaylist,
                        onSelectPlaylistTab = { activeTab = AudioPlayerTab.PLAYLISTS },
                        onOpenNote = { onThreadClick(it.id) },
                        onOpenProfile = onProfileClick,
                    )
                }
            }
        }

        uiState.statusMessage?.let { message ->
            Text(
                text = message,
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .clickable { viewModel.clearStatus() }
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
        }
        PlaybackControls(
            snapshot = snapshot,
            controller = controller,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New audio playlist") },
            text = {
                Column {
                    Text(
                        "This is published as a standard NIP-51 kind-30003 bookmark set so other Nostr clients can read it.",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it.take(64) },
                        label = { Text("playlist name") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newPlaylistName.isNotBlank() && !uiState.isCreatingPlaylist,
                    onClick = {
                        viewModel.createPlaylist(newPlaylistName)
                        newPlaylistName = ""
                        showCreatePlaylist = false
                    },
                ) { Text(if (uiState.isCreatingPlaylist) "creating…" else "create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylist = false }) { Text("cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistsHome(
    playlists: List<BookmarkFolderUi>,
    isSyncing: Boolean,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("your playlists", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "Dedicated online audio lists · stored as NIP-51 bookmark sets",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            IconButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "Create playlist", tint = BonyColors.Accent)
            }
        }
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (isSyncing) "loading online playlists…" else "no audio playlists yet · tap + to create one",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(playlists, key = { it.id }) { playlist ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(playlist.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            AudioPlaylists.displayName(playlist.name),
                            style = BonyType.body.copy(color = BonyColors.Text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text("online NIP-51 bookmark set", style = BonyType.caption.copy(color = BonyColors.TextMute))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetails(
    playlist: BookmarkFolderUi,
    tracks: List<AudioTrack>,
    profiles: Map<String, ProfileContent>,
    isConnecting: Boolean,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onOpenNote: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("← playlists", style = BonyType.meta.copy(color = BonyColors.Accent), modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text(
                text = "${tracks.size} song${if (tracks.size == 1) "" else "s"}",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (isConnecting) "connecting…" else "play all ▶",
                style = BonyType.tag.copy(color = if (isConnecting || tracks.isEmpty()) BonyColors.TextMute else BonyColors.Accent),
                modifier = Modifier.clickable(enabled = !isConnecting && tracks.isNotEmpty(), onClick = onPlayAll),
            )
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("this playlist has no audio posts yet", style = BonyType.body.copy(color = BonyColors.TextMute))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(tracks, key = { it.event.id }) { track ->
                    AudioTrackRow(
                        track = track,
                        profile = profiles[track.event.pubkey],
                        isConnecting = isConnecting,
                        onPlay = { onPlayTrack(tracks.indexOf(track)) },
                        onOpenNote = { onOpenNote(track.event) },
                        onOpenProfile = { onOpenProfile(track.event.pubkey) },
                        onRemove = { onRemove(track.event.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoverFeed(
    events: List<Event>,
    tracks: List<AudioTrack>,
    profiles: Map<String, ProfileContent>,
    playlists: List<BookmarkFolderUi>,
    loading: Boolean,
    isConnecting: Boolean,
    onPlayTrack: (AudioTrack) -> Unit,
    onAddToPlaylist: (Event, String) -> Unit,
    onSelectPlaylistTab: () -> Unit,
    onOpenNote: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    if (loading && events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BonyColors.Accent)
        }
    } else if (events.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("no audio posts tagged #TboneMusic yet", style = BonyType.body.copy(color = BonyColors.TextMute))
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tracks, key = { it.event.id }) { track ->
                AudioPostRow(
                    track = track,
                    profile = profiles[track.event.pubkey],
                    playlists = playlists,
                    isConnecting = isConnecting,
                    onPlay = { onPlayTrack(track) },
                    onAddToPlaylist = { folderId -> onAddToPlaylist(track.event, folderId) },
                    onSelectPlaylistTab = onSelectPlaylistTab,
                    onOpenNote = { onOpenNote(track.event) },
                    onOpenProfile = { onOpenProfile(track.event.pubkey) },
                )
            }
        }
    }
}

@Composable
private fun AudioTrackRow(
    track: AudioTrack,
    profile: ProfileContent?,
    isConnecting: Boolean,
    onPlay: () -> Unit,
    onOpenNote: () -> Unit,
    onOpenProfile: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(label = "Play ${track.title}", onClick = onPlay, enabled = !isConnecting)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                profile?.bestName ?: track.artist,
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onOpenProfile),
            )
            Text("open post →", style = BonyType.caption.copy(color = BonyColors.Accent), modifier = Modifier.clickable(onClick = onOpenNote))
        }
        Text("remove", style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.clickable(onClick = onRemove))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun AudioPostRow(
    track: AudioTrack,
    profile: ProfileContent?,
    playlists: List<BookmarkFolderUi>,
    isConnecting: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onSelectPlaylistTab: () -> Unit,
    onOpenNote: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    var menuExpanded by remember(track.event.id) { mutableStateOf(false) }
    val noteText = remember(track.event.id, track.url) {
        track.event.content.replace(track.url, " ").replace(Regex("\\s+"), " ").trim()
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile?.bestName ?: track.artist,
                style = BonyType.name.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f).clickable(onClick = onOpenProfile),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatPostDate(track.event.createdAt),
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
        if (noteText.isNotBlank()) {
            Text(
                text = noteText,
                style = BonyType.body.copy(color = BonyColors.TextDim),
                modifier = Modifier.padding(top = 5.dp, bottom = 8.dp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayButton(label = "Play ${track.title}", onClick = onPlay, enabled = !isConnecting)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("audio · #TboneMusic", style = BonyType.caption.copy(color = BonyColors.TextMute))
            }
            Box {
                Text(
                    "add to playlist",
                    style = BonyType.caption.copy(color = if (playlists.isEmpty()) BonyColors.TextMute else BonyColors.Accent),
                    modifier = Modifier.clickable {
                        if (playlists.isEmpty()) onSelectPlaylistTab() else menuExpanded = true
                    },
                )
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text(AudioPlaylists.displayName(playlist.name)) },
                            onClick = {
                                onAddToPlaylist(playlist.id)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
        Text("open post →", style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.clickable(onClick = onOpenNote).padding(top = 6.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun PlaybackControls(
    snapshot: PlaybackSnapshot,
    controller: MediaController?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(BonyColors.Surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snapshot.title.ifBlank { "nothing playing" },
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        controller == null -> "connecting to playback service…"
                        snapshot.isBuffering -> "buffering…"
                        snapshot.artist.isNotBlank() -> snapshot.artist
                        else -> "audio player · lock-screen and notification controls"
                    },
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (snapshot.isBuffering) "…" else if (snapshot.isPlaying) "Ⅱ" else "▶",
                style = BonyType.title.copy(
                    color = if (controller == null || snapshot.title.isBlank()) BonyColors.TextMute else BonyColors.Accent,
                ),
                modifier = Modifier
                    .semantics { contentDescription = if (snapshot.isPlaying) "Pause" else "Play" }
                    .clickable(enabled = controller != null && snapshot.title.isNotBlank()) {
                        if (snapshot.isPlaying) controller?.pause() else controller?.play()
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (snapshot.durationMs > 0L) {
            Slider(
                value = (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f),
                onValueChange = { fraction -> controller?.seekTo((snapshot.durationMs * fraction).toLong()) },
                enabled = controller != null,
                modifier = Modifier.height(28.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(snapshot.positionMs), style = BonyType.caption.copy(color = BonyColors.TextMute))
                Text(formatDuration(snapshot.durationMs), style = BonyType.caption.copy(color = BonyColors.TextMute))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "|◀",
                style = BonyType.tag.copy(color = if (snapshot.hasPrevious) BonyColors.Accent else BonyColors.TextMute),
                modifier = Modifier.clickable(enabled = controller != null && snapshot.hasPrevious) { controller?.seekToPreviousMediaItem() }.padding(10.dp),
            )
            Text(
                "stop",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable(enabled = controller != null) { controller?.stop() }.padding(10.dp),
            )
            Text(
                "▶|",
                style = BonyType.tag.copy(color = if (snapshot.hasNext) BonyColors.Accent else BonyColors.TextMute),
                modifier = Modifier.clickable(enabled = controller != null && snapshot.hasNext) { controller?.seekToNextMediaItem() }.padding(10.dp),
            )
        }
    }
}

@Composable
private fun PlayerTabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .appBorder(1.dp, if (selected) BonyColors.AccentDim else BonyColors.Rule)
            .background(if (selected) BonyColors.AccentBg else BonyColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = BonyType.tag.copy(color = if (selected) BonyColors.Accent else BonyColors.TextMute))
    }
}

@Composable
private fun PlayButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) BonyColors.AccentBg else BonyColors.SurfaceAlt)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text("▶", style = BonyType.body.copy(color = if (enabled) BonyColors.Accent else BonyColors.TextMute, fontSize = 14.sp))
    }
}

private fun Event.toAudioTrack(profiles: Map<String, ProfileContent>): AudioTrack? {
    val url = audioUrl(this) ?: return null
    val artist = profiles[pubkey]?.bestName?.takeIf { it.isNotBlank() } ?: pubkey.take(12)
    return AudioTrack(
        event = this,
        url = url,
        title = audioTrackTitle(this, url),
        artist = artist,
    )
}

private fun playQueue(controller: MediaController?, tracks: List<AudioTrack>, startIndex: Int) {
    if (controller == null || tracks.isEmpty()) return
    val mediaItems = tracks.map { track ->
        MediaItem.Builder()
            .setUri(track.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setSubtitle("#TboneMusic")
                    .build(),
            )
            .build()
    }
    controller.setMediaItems(mediaItems, startIndex.coerceIn(mediaItems.indices), C.TIME_UNSET)
    controller.prepare()
    controller.play()
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatPostDate(createdAt: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(createdAt))
}.getOrDefault("")
