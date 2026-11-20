package social.tbone.ui.audio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import social.tbone.media.audio.audioIdentity
import social.tbone.ui.compose.ComposeViewModel
import social.tbone.ui.compose.ComposeEffect
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
    val album: String = "",
    val artwork: String? = null,
)

private data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: ByteArray? = null,
    val artworkUri: String? = null,
    val poster: String = "",
    val eventId: String = "",
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
)

@Composable
fun AudioPlayerScreen(
    initialUrl: String = "",
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit,
    onProfileClick: (pubkey: String) -> Unit,
    viewModel: AudioPlayerViewModel = hiltViewModel(),
    uploadViewModel: ComposeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val backgroundPlaybackEnabled = LocalMediaBackgroundPlayback.current
    val controller = rememberPlaybackController()
    var activeTab by rememberSaveable { mutableStateOf(AudioPlayerTab.PLAYER) }
    val uploadState by uploadViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadViewModel.attachMedia(uri, "audio", preserveOriginalAudio = true)
    }
    LaunchedEffect(uploadViewModel) {
        uploadViewModel.uploadedMediaUrls.collect { url -> uploadViewModel.publish(url, tagHashtags = listOf("TboneMusic")) }
    }
    LaunchedEffect(uploadViewModel, viewModel) {
        uploadViewModel.effects.collect { effect ->
            if (effect is ComposeEffect.Published) viewModel.refreshMusicFeed()
        }
    }
    LaunchedEffect(uploadViewModel) {
        uploadViewModel.uploadErrors.collect { message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
    }
    var onlinePlaylist by remember { mutableStateOf(true) }
    var showAbout by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var snapshot by remember(controller) { mutableStateOf(PlaybackSnapshot()) }

    fun readPlayback(p: MediaController?) {
        if (p == null) {
            snapshot = PlaybackSnapshot()
            return
        }
        val item = p.currentMediaItem
        val metadata = p.mediaMetadata
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        snapshot = PlaybackSnapshot(
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            artwork = metadata.artworkData,
            artworkUri = metadata.artworkUri?.toString(),
            poster = item?.mediaMetadata?.extras?.getString("poster").orEmpty(),
            eventId = item?.mediaMetadata?.extras?.getString("eventId").orEmpty(),
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

    LaunchedEffect(controller, initialUrl) {
        if (initialUrl.isNotBlank() && controller != null && controller.currentMediaItem?.localConfiguration?.uri?.toString() != initialUrl) {
            controller.setMediaItem(MediaItem.fromUri(initialUrl))
            controller.prepare()
            controller.play()
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
                    text = if (selectedPlaylist != null && activeTab == AudioPlayerTab.LIBRARY) {
                        AudioPlaylists.displayName(selectedPlaylist.name)
                    } else "NIP-51 playlists · #TboneMusic",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, "More audio options", tint = BonyColors.TextMute)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("About this track") }, onClick = { showMenu = false; showAbout = true })
                    DropdownMenuItem(text = { Text("Library") }, onClick = { showMenu = false; activeTab = AudioPlayerTab.LIBRARY })
                    DropdownMenuItem(text = { Text("Stop playback") }, onClick = { showMenu = false; controller?.stop() })
                }
            }
            IconButton(
                onClick = {
                    if (activeTab == AudioPlayerTab.DISCOVER) viewModel.refreshMusicFeed()
                    else viewModel.refreshPlaylists()
                },
            ) {
                if (uiState.isSyncingPlaylists && activeTab == AudioPlayerTab.LIBRARY) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = BonyColors.Accent)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = BonyColors.TextMute)
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (activeTab) {
                AudioPlayerTab.PLAYER -> FullPlayer(snapshot, controller)
                AudioPlayerTab.LIBRARY -> {
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
                        onSelectPlaylistTab = { activeTab = AudioPlayerTab.LIBRARY },
                        onOpenNote = { onThreadClick(it.id) },
                        onOpenProfile = onProfileClick,
                    )
                    FloatingActionButton(
                        onClick = { if (!uploadState.isUploading && !uploadState.isPublishing) uploadPicker.launch("audio/*") },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                        containerColor = BonyColors.Accent,
                    ) { Icon(Icons.Default.Add, "Upload original audio") }
                }
            }
        }
        if (uploadState.isUploading || uploadState.isPublishing) {
            Text(if (uploadState.isUploading) "Uploading original audio · metadata preserved" else "Publishing #TboneMusic…",
                style = BonyType.caption.copy(color = BonyColors.Accent), modifier = Modifier.padding(12.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BonyColors.Accent)
        }
        uploadState.error?.let { Text(it, color = BonyColors.Danger, modifier = Modifier.padding(12.dp)) }

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
        if (activeTab != AudioPlayerTab.PLAYER && snapshot.title.isNotBlank()) {
            MiniPlayer(snapshot, controller, onOpen = { activeTab = AudioPlayerTab.PLAYER })
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).clip(RoundedCornerShape(24.dp))
            .background(BonyColors.Surface), verticalAlignment = Alignment.CenterVertically) {
            AudioPlayerTab.entries.forEach { tab ->
                Column(Modifier.weight(1f).clickable { activeTab = tab }.padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val color = if (activeTab == tab) BonyColors.Accent else BonyColors.TextMute
                    Icon(when (tab) {
                        AudioPlayerTab.PLAYER -> Icons.Default.MusicNote
                        AudioPlayerTab.LIBRARY -> Icons.Default.LibraryMusic
                        AudioPlayerTab.DISCOVER -> Icons.Default.Explore
                    }, tab.name.lowercase(), tint = color)
                    Text(tab.name.lowercase().replaceFirstChar { it.uppercase() }, style = BonyType.caption.copy(color = color))
                }
            }
        }
    }
    if (showAbout) {
        AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("About this track") },
            text = { Column {
                Text(snapshot.title.ifBlank { "Nothing playing" })
                Text("Artist: ${snapshot.artist.ifBlank { "Unknown artist" }}")
                Text("Album: ${snapshot.album.ifBlank { "Unknown album" }}")
                Text("Shared by: ${snapshot.poster.ifBlank { "Unknown poster" }}")
                if (snapshot.eventId.isNotBlank()) TextButton(onClick = { showAbout = false; onThreadClick(snapshot.eventId) }) { Text("View original post") }
            } }, confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Done") } })
    }

    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("New audio playlist") },
            text = {
                Column {
                    Text(
                        "Online playlists sync using the same encrypted NIP-51 system as bookmarks. Offline playlists stay on this device (audio is streamed unless already cached).",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (onlinePlaylist) "Online · sync with bookmarks" else "Offline · device only", modifier = Modifier.weight(1f))
                        Switch(checked = onlinePlaylist, onCheckedChange = { onlinePlaylist = it })
                    }
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
                        viewModel.createPlaylist(newPlaylistName, onlinePlaylist)
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
                    "Offline & online playlists · bookmark sync",
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
                        Text(if (playlist.isOnline) "Online · synced with bookmarks" else "Offline · device only", style = BonyType.caption.copy(color = BonyColors.TextMute))
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
        TrackArt(track.artwork, null, Modifier.size(48.dp).clickable(onClick = onPlay))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArt(track.artwork, null, Modifier.size(56.dp).clickable(onClick = onPlay))
            PlayButton(label = "Play ${track.title}", onClick = onPlay, enabled = !isConnecting)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), style = BonyType.caption.copy(color = BonyColors.TextMute))
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
    val identity = audioIdentity(this, url)
    return AudioTrack(
        event = this,
        url = url,
        title = audioTrackTitle(this, url),
        artist = identity.artist,
        album = identity.album,
        artwork = identity.artwork,
    )
}

private fun playQueue(controller: MediaController?, tracks: List<AudioTrack>, startIndex: Int) {
    if (controller == null || tracks.isEmpty()) return
    val mediaItems = tracks.map { track ->
        MediaItem.Builder()
            .setUri(track.url)
            .setMediaId(track.event.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artwork?.let(android.net.Uri::parse))
                    .setExtras(android.os.Bundle().apply {
                        putString("poster", track.event.pubkey)
                        putString("eventId", track.event.id)
                    })
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

@Composable
private fun TrackArt(uri: String?, bytes: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(BonyColors.SurfaceAlt), contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(bitmap, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            uri != null -> coil.compose.AsyncImage(uri, "Album art", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else -> Icon(Icons.Default.MusicNote, "No album art", Modifier.size(36.dp), tint = BonyColors.Accent)
        }
    }
}

@Composable
private fun MiniPlayer(snapshot: PlaybackSnapshot, controller: MediaController?, onOpen: () -> Unit) {
    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
        .background(BonyColors.AccentBg).clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArt(snapshot.artworkUri, snapshot.artwork, Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(snapshot.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(snapshot.artist.ifBlank { "Unknown artist" }, style = BonyType.caption.copy(color = BonyColors.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, "Previous track", tint = BonyColors.Accent) }
        IconButton(onClick = { if (snapshot.isPlaying) controller?.pause() else controller?.play() }, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(BonyColors.SurfaceAlt)) {
            Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "Pause" else "Play", tint = BonyColors.Accent)
        }
        IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Default.SkipNext, "Next track", tint = BonyColors.Accent) }
    }
}

@Composable
private fun FullPlayer(snapshot: PlaybackSnapshot, controller: MediaController?) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        TrackArt(snapshot.artworkUri, snapshot.artwork, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(24.dp))
        Text(snapshot.title.ifBlank { "Your music, front and centre" }, style = BonyType.title.copy(color = BonyColors.Text))
        Text(snapshot.artist.ifBlank { "Choose a track in Library or Discover" }, style = BonyType.body.copy(color = BonyColors.TextMute), modifier = Modifier.padding(top = 8.dp))
        if (snapshot.album.isNotBlank()) Text(snapshot.album, style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        PlaybackControls(snapshot, controller)
    }
}
