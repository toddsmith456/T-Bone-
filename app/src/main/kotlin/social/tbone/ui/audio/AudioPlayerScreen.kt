package social.tbone.ui.audio

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import social.tbone.ui.compose.AudioUploadStage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
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
import social.tbone.media.playback.*
import social.tbone.media.playback.AudioPlaybackState as PlaybackSnapshot
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
    val artworkBytes: ByteArray? = null,
    val poster: String = "",
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AudioPlayerScreen(
    initialUrl: String = "",
    initialPoster: String = "",
    initialEventId: String = "",
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit,
    onProfileClick: (pubkey: String) -> Unit,
    viewModel: AudioPlayerViewModel = hiltViewModel(),
    uploadViewModel: ComposeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val metadata by viewModel.metadata.collectAsStateWithLifecycle()
    var connectionRetry by remember { mutableStateOf(0) }
    val connection = rememberPlaybackConnection(retry = connectionRetry)
    val controller = connection.controller
    val snapshot = rememberAudioPlaybackState(controller, connection.error)
    val played by viewModel.podcastPlayed.collectAsStateWithLifecycle()
    val progressRevision by viewModel.progressRevision.collectAsStateWithLifecycle()
    LaunchedEffect(snapshot.urlKey, snapshot.podcast, progressRevision) {
        if (snapshot.podcast) snapshot.urlKey?.let { viewModel.readPlayed(it) }
    }
    val listStates = rememberSaveableStateHolder()
    var showQueue by remember { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(AudioPlayerTab.PLAYER) }
    val uploadState by uploadViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var uploadCategory by rememberSaveable { mutableStateOf(AudioCategory.MUSIC) }
    var showUploadChoice by remember { mutableStateOf(false) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) uploadViewModel.attachMedia(uri, "audio", preserveOriginalAudio = true, audioCategory = uploadCategory.hashtag, recoverableAudio = true)
    }
    LaunchedEffect(uploadViewModel) {
        uploadViewModel.uploadedMediaUrls.collect { url -> uploadViewModel.publish(url) }
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
    LaunchedEffect(uiState.statusMessage) {
        if (uiState.statusMessage != null) {
            delay(5_000)
            viewModel.clearStatus()
        }
    }

    var initialSelectionHandled by rememberSaveable(initialUrl) { mutableStateOf(false) }
    LaunchedEffect(controller, initialUrl, initialSelectionHandled) {
        if (!initialSelectionHandled && controller != null && initialUrl.isNotBlank()) {
            selectInlineAudio(controller, AudioTrackIdentity(initialUrl, initialEventId, initialPoster), autoplay = true)
            initialSelectionHandled = true
        }
    }
    BackHandler(enabled = uiState.selectedPlaylistId != null && activeTab == AudioPlayerTab.LIBRARY) { viewModel.selectPlaylist(null) }
    val selectedPlaylist = uiState.playlists.firstOrNull { it.id == uiState.selectedPlaylistId }
    val playlistTracks = remember(uiState.playlistTracks, profiles, metadata) {
        uiState.playlistTracks.mapNotNull { bookmark -> bookmark.event.toAudioTrack(profiles)?.withMetadata(metadata) }
    }
    val discoveredTracks = remember(uiState.discoveredEvents, profiles, metadata) {
        uiState.discoveredEvents.mapNotNull { it.toAudioTrack(profiles)?.withMetadata(metadata) }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalLoadAudioMetadata provides viewModel::loadMetadata) {
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
            IconButton(onClick = { if (activeTab == AudioPlayerTab.LIBRARY && uiState.selectedPlaylistId != null) viewModel.selectPlaylist(null) else onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BonyColors.TextMute)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("audio player", style = BonyType.title.copy(color = BonyColors.Text))
                Text(
                    text = if (selectedPlaylist != null && activeTab == AudioPlayerTab.LIBRARY) {
                        AudioPlaylists.displayName(selectedPlaylist.name)
                    } else "Music & podcasts · synced playlists",
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
                    DropdownMenuItem(text = { Text("Queue (${snapshot.queue.size})") }, onClick = { showMenu = false; showQueue = true })
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
                AudioPlayerTab.PLAYER -> FullPlayer(snapshot, controller, played, onPlayed = { value -> snapshot.urlKey?.let { viewModel.markPlayed(it, value) } }, onQueue = { showQueue = true })
                AudioPlayerTab.LIBRARY -> {
                    if (selectedPlaylist == null) {
                        listStates.SaveableStateProvider("audio_library_home") {
                        PlaylistsHome(
                            playlists = uiState.playlists,
                            isSyncing = uiState.isSyncingPlaylists,
                            onCreate = { showCreatePlaylist = true },
                            onSelect = viewModel::selectPlaylist,
                        )
                        }
                    } else {
                        listStates.SaveableStateProvider("audio_playlist_${selectedPlaylist.id}") {
                        PlaylistDetails(
                            playlist = selectedPlaylist,
                            tracks = playlistTracks,
                            profiles = profiles,
                            isConnecting = controller == null,
                            onBack = { viewModel.selectPlaylist(null) },
                            onPlayAll = { playQueue(controller, playlistTracks, 0, "Playlist · " + AudioPlaylists.displayName(selectedPlaylist.name)) },
                            onPlayTrack = { index -> playQueue(controller, playlistTracks, index, "Playlist · " + AudioPlaylists.displayName(selectedPlaylist.name)) },
                            onOpenNote = { onThreadClick(it.id) },
                            onOpenProfile = onProfileClick,
                            onRemove = viewModel::removeEventFromPlaylist,
                        )
                        }
                    }
                }
                AudioPlayerTab.DISCOVER -> {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AudioCategory.entries.forEach { category ->
                                PlayerTabChip(category.label, uiState.category == category, { viewModel.selectCategory(category) }, Modifier.weight(1f))
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AudioAudience.entries.forEach { audience ->
                                PlayerTabChip(audience.name.lowercase().replaceFirstChar { it.uppercase() }, uiState.audience == audience,
                                    { viewModel.selectAudience(audience) }, Modifier.weight(1f))
                            }
                        }
                    listStates.SaveableStateProvider("audio_discover_${uiState.category}_${uiState.audience}") {
                    DiscoverFeed(
                        emptyMessage = if (uiState.audience == AudioAudience.FOLLOWS) "No ${uiState.category.label.lowercase()} from your follows yet. Try Global or follow more creators."
                            else "No audio posts tagged #${uiState.category.hashtag} yet",
                        events = uiState.discoveredEvents,
                        tracks = discoveredTracks,
                        profiles = profiles,
                        playlists = uiState.playlists,
                        loading = uiState.isLoadingFeed,
                        isConnecting = controller == null,
                        onPlayTrack = { track -> playQueue(controller, listOf(track), 0, "${uiState.category.label} · ${uiState.audience.name.lowercase()}") },
                        onQueueTrack = { track ->
                            controller?.let { player ->
                                player.addMediaItem(track.mediaItem("${uiState.category.label} · ${uiState.audience.name.lowercase()}"))
                                if (player.mediaItemCount == 1) player.prepare()
                            }
                        },
                        onAddToPlaylist = viewModel::addEventToPlaylist,
                        onSelectPlaylistTab = { activeTab = AudioPlayerTab.LIBRARY },
                        onOpenNote = { onThreadClick(it.id) },
                        onOpenProfile = onProfileClick,
                    )
                    }
                    }
                    FloatingActionButton(
                        onClick = { if (!uploadState.isUploading && !uploadState.isPublishing) showUploadChoice = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                        containerColor = BonyColors.Accent,
                    ) { Icon(Icons.Default.Add, "Upload original audio") }
                }
            }
        }
        snapshot.error?.let { error ->
            Column(Modifier.fillMaxWidth().background(BonyColors.SurfaceAlt).padding(12.dp)) {
                Text(error, color = BonyColors.Danger)
                androidx.compose.foundation.layout.FlowRow {
                    TextButton(onClick = { if (controller == null) connectionRetry++ else retryAudio(controller) }) { Text("Retry playback") }
                    if (snapshot.eventId.isNotBlank()) TextButton(onClick = { onThreadClick(snapshot.eventId) }) { Text("Original post") }
                }
            }
        }
        if (snapshot.phase == AudioPhase.CONNECTING) Text("Connecting to playback…", style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.padding(12.dp))
        if (uploadState.audioUploadStage != AudioUploadStage.IDLE) {
            Column(Modifier.fillMaxWidth().background(BonyColors.SurfaceAlt).padding(12.dp)) {
                Text("${if (uploadState.audioUploadCategory == "TbonePodcast") "Podcast" else "Music"} · ${uploadState.audioUploadName}", maxLines = 1, overflow = TextOverflow.Ellipsis, color = BonyColors.Text)
                Text(when (uploadState.audioUploadStage) {
                    AudioUploadStage.PREPARING -> "Preparing original file · no compression"
                    AudioUploadStage.UPLOADING -> "Uploading original file…"
                    AudioUploadStage.UPLOADED -> "File uploaded · preparing publication"
                    AudioUploadStage.SIGNING -> "Waiting for note signing…"
                    AudioUploadStage.PUBLISHING -> "Waiting for relay confirmation…"
                    AudioUploadStage.COMPLETE -> "Published · confirmed by a relay"
                    AudioUploadStage.UPLOAD_FAILED -> "Upload failed"
                    AudioUploadStage.PUBLISH_FAILED -> "Publication not confirmed · uploaded file retained"
                    AudioUploadStage.CANCELLED -> "Upload cancelled. The server may already have stored the file."
                    else -> ""
                }, style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (uploadState.isUploading || uploadState.isPublishing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = BonyColors.Accent)
                uploadState.audioOperationError?.let { Text(it, color = BonyColors.Danger, style = BonyType.caption) }
                androidx.compose.foundation.layout.FlowRow {
                    if (uploadState.audioUploadStage in setOf(AudioUploadStage.PREPARING, AudioUploadStage.UPLOADING)) TextButton(onClick = uploadViewModel::cancelAudioUpload) { Text("Cancel upload") }
                    if (uploadState.audioUploadStage in setOf(AudioUploadStage.UPLOAD_FAILED, AudioUploadStage.PUBLISH_FAILED)) TextButton(onClick = uploadViewModel::retryAudioOperation, enabled = !uploadState.isUploading && !uploadState.isPublishing) {
                        Text(if (uploadState.audioRecoveryUrl != null) "Retry publish (reuse file)" else "Retry upload")
                    }
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
        if (activeTab != AudioPlayerTab.PLAYER && snapshot.title.isNotBlank()) {
            MiniPlayer(snapshot, controller, onOpen = { activeTab = AudioPlayerTab.PLAYER })
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(10.dp).clip(RoundedCornerShape(24.dp))
            .background(BonyColors.Surface), verticalAlignment = Alignment.CenterVertically) {
            AudioPlayerTab.entries.forEach { tab ->
                Column(Modifier.weight(1f).selectable(selected = activeTab == tab, role = Role.Tab, onClick = { activeTab = tab }).defaultMinSize(minHeight = 48.dp).padding(vertical = 14.dp),
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
    if (showQueue) AudioQueueSheet(snapshot, controller, onDismiss = { showQueue = false })
    if (showUploadChoice) {
        AlertDialog(onDismissRequest = { showUploadChoice = false }, title = { Text("Upload original audio") },
            text = { Text("Choose the discovery category. Original audio bytes and metadata are preserved; no compression is applied.") },
            confirmButton = { TextButton(onClick = { uploadCategory = AudioCategory.MUSIC; showUploadChoice = false; uploadPicker.launch("audio/*") }) { Text("Music") } },
            dismissButton = { TextButton(onClick = { uploadCategory = AudioCategory.PODCASTS; showUploadChoice = false; uploadPicker.launch("audio/*") }) { Text("Podcasts") } })
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
                        onValueChange = { newPlaylistName = it.take(48 - AudioPlaylists.TITLE_PREFIX.length) },
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
}

@Composable
private fun PlaylistsHome(
    playlists: List<BookmarkFolderUi>,
    isSyncing: Boolean,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
    val listState = rememberLazyListState()
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
    emptyMessage: String,
    events: List<Event>,
    tracks: List<AudioTrack>,
    profiles: Map<String, ProfileContent>,
    playlists: List<BookmarkFolderUi>,
    loading: Boolean,
    isConnecting: Boolean,
    onPlayTrack: (AudioTrack) -> Unit,
    onQueueTrack: (AudioTrack) -> Unit,
    onAddToPlaylist: (Event, String) -> Unit,
    onSelectPlaylistTab: () -> Unit,
    onOpenNote: (Event) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    if (loading && events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = BonyColors.Accent)
        }
    } else if (events.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(emptyMessage, style = BonyType.body.copy(color = BonyColors.TextMute))
        }
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(tracks, key = { it.event.id }) { track ->
                AudioPostRow(
                    track = track,
                    profile = profiles[track.event.pubkey],
                    playlists = playlists,
                    isConnecting = isConnecting,
                    onPlay = { onPlayTrack(track) },
                    onQueue = { onQueueTrack(track) },
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
    var menu by remember(track.event.id) { mutableStateOf(false) }
    var about by remember(track.event.id) { mutableStateOf(false) }
    val context = LocalContext.current
    if (about) AudioTrackAboutDialog(track, profile, onDismiss = { about = false })
    val loadMetadata = LocalLoadAudioMetadata.current
    val autoLoad = social.tbone.ui.media.LocalImageLoadMode.current != social.tbone.settings.ImageLoadMode.OFF
    LaunchedEffect(track.url, autoLoad) { loadMetadata(track.url, autoLoad) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArt(track.artwork, track.artworkBytes, Modifier.size(48.dp).clickable(onClick = onPlay))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                track.artist,
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                maxLines = 1,
            )
            Text("Shared by ${profile?.bestName ?: track.poster}", style = BonyType.caption.copy(color = BonyColors.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Track options for ${track.title}", tint = BonyColors.TextMute) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("About this track") }, onClick = { menu = false; about = true })
                DropdownMenuItem(text = { Text("Original post") }, onClick = { menu = false; onOpenNote() })
                DropdownMenuItem(text = { Text("View poster profile") }, onClick = { menu = false; onOpenProfile() })
                DropdownMenuItem(text = { Text("Share original post") }, onClick = { menu = false; shareAudioPost(context, track.event) })
                DropdownMenuItem(text = { Text("Remove from playlist") }, onClick = { menu = false; onRemove() })
            }
        }
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
    onQueue: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onSelectPlaylistTab: () -> Unit,
    onOpenNote: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    var about by remember(track.event.id) { mutableStateOf(false) }
    val context = LocalContext.current
    if (about) AudioTrackAboutDialog(track, profile, onDismiss = { about = false })
    val loadMetadata = LocalLoadAudioMetadata.current
    val autoLoad = social.tbone.ui.media.LocalImageLoadMode.current != social.tbone.settings.ImageLoadMode.OFF
    LaunchedEffect(track.url, autoLoad) { loadMetadata(track.url, autoLoad) }
    var menuExpanded by remember(track.event.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Shared by ${profile?.bestName ?: track.poster}",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatPostDate(track.event.createdAt),
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArt(track.artwork, track.artworkBytes, Modifier.size(56.dp).clickable(onClick = onPlay))
            PlayButton(label = "Play ${track.title}", onClick = onPlay, enabled = !isConnecting)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(track.title, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · "), style = BonyType.caption.copy(color = BonyColors.TextMute))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, "Track options for ${track.title}", tint = BonyColors.TextMute)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("About this track") }, onClick = { menuExpanded = false; about = true })
                    DropdownMenuItem(text = { Text("Original post") }, onClick = { menuExpanded = false; onOpenNote() })
                    DropdownMenuItem(text = { Text("View poster profile") }, onClick = { menuExpanded = false; onOpenProfile() })
                    DropdownMenuItem(text = { Text("Share original post") }, onClick = { menuExpanded = false; shareAudioPost(context, track.event) })
                    DropdownMenuItem(text = { Text("Add to queue") }, enabled = !isConnecting, onClick = { menuExpanded = false; onQueue() })
                    if (playlists.isEmpty()) DropdownMenuItem(text = { Text("Create a playlist…") }, onClick = { menuExpanded = false; onSelectPlaylistTab() })
                    playlists.forEach { playlist ->
                        DropdownMenuItem(
                            text = { Text("Add to playlist · " + AudioPlaylists.displayName(playlist.name)) },
                            onClick = {
                                onAddToPlaylist(playlist.id)
                                menuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun PlaybackControls(snapshot: PlaybackSnapshot, controller: MediaController?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Slider(value = if (snapshot.durationMs > 0) (snapshot.positionMs.toFloat() / snapshot.durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { controller?.seekTo((snapshot.durationMs * it).toLong()) },
            enabled = controller != null && snapshot.durationMs > 0,
            modifier = Modifier.semantics { contentDescription = "Playback position"; stateDescription = "${formatDuration(snapshot.positionMs)} of ${formatDuration(snapshot.durationMs)}" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(snapshot.positionMs), style = BonyType.caption.copy(color = BonyColors.TextMute))
            Text(if (snapshot.isBuffering) "Buffering…" else formatDuration(snapshot.durationMs), style = BonyType.caption.copy(color = BonyColors.TextMute))
        }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = controller != null && snapshot.hasPrevious) {
                Icon(Icons.Default.SkipPrevious, "Previous track", Modifier.size(32.dp), tint = if (snapshot.hasPrevious) BonyColors.Text else BonyColors.TextMute)
            }
            IconButton(onClick = { controller?.let(::toggleAudio) },
                enabled = controller != null && snapshot.title.isNotBlank(),
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(BonyColors.AccentBg)) {
                Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (snapshot.isPlaying) "Pause" else "Play", Modifier.size(40.dp), tint = BonyColors.Accent)
            }
            IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = controller != null && snapshot.hasNext) {
                Icon(Icons.Default.SkipNext, "Next track", Modifier.size(32.dp), tint = if (snapshot.hasNext) BonyColors.Text else BonyColors.TextMute)
            }
        }
    }
}

@Composable
private fun PlayerTabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .appBorder(1.dp, if (selected) BonyColors.AccentDim else BonyColors.Rule)
            .background(if (selected) BonyColors.AccentBg else BonyColors.Surface)
            .defaultMinSize(minHeight = 48.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
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
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) BonyColors.AccentBg else BonyColors.SurfaceAlt)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.PlayArrow, label, tint = if (enabled) BonyColors.Accent else BonyColors.TextMute)
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
        poster = profiles[pubkey]?.bestName ?: pubkey,
    )
}

private fun AudioTrack.mediaItem(origin: String): MediaItem = AudioTrackIdentity(
    url = url, eventId = event.id, poster = poster, origin = origin,
    podcast = event.parsedTags.any { it.name == "t" && it.value()?.equals("tbonepodcast", true) == true },
    title = title.takeUnless { it == audioFilename(url) }, artist = artist.takeUnless { it == "Unknown artist" }, album = album.takeIf { it.isNotBlank() },
    artworkUri = artwork, artwork = artworkBytes,
).mediaItem()

private fun playQueue(controller: MediaController?, tracks: List<AudioTrack>, startIndex: Int, origin: String) {
    if (controller == null || tracks.isEmpty()) return
    controller.setMediaItems(tracks.map { it.mediaItem(origin) }, startIndex.coerceIn(tracks.indices), C.TIME_UNSET)
    controller.prepare()
    controller.play()
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) / 1_000L)
    return if (totalSeconds >= 3600) "%d:%02d:%02d".format(totalSeconds / 3600, (totalSeconds / 60) % 60, totalSeconds % 60)
    else "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatPostDate(createdAt: Long): String = runCatching {
    DateTimeFormatter.ofPattern("MMM d")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(createdAt))
}.getOrDefault("")

@Composable
private fun TrackArt(uri: String?, bytes: ByteArray?, modifier: Modifier = Modifier) {
    val bitmap = remember(bytes) { bytes?.let {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
        val options = android.graphics.BitmapFactory.Options().apply {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
            inSampleSize = sample
        }
        android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size, options)?.asImageBitmap()
    } }
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
        IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = controller != null && snapshot.hasPrevious) { Icon(Icons.Default.SkipPrevious, "Previous track", tint = if (snapshot.hasPrevious) BonyColors.Accent else BonyColors.TextMute) }
        IconButton(onClick = { controller?.let(::toggleAudio) }, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(BonyColors.SurfaceAlt)) {
            Icon(if (snapshot.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (snapshot.isPlaying) "Pause" else "Play", tint = BonyColors.Accent)
        }
        IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = controller != null && snapshot.hasNext) { Icon(Icons.Default.SkipNext, "Next track", tint = if (snapshot.hasNext) BonyColors.Accent else BonyColors.TextMute) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FullPlayer(snapshot: PlaybackSnapshot, controller: MediaController?, played: Boolean, onPlayed: (Boolean) -> Unit, onQueue: () -> Unit) {
    var remaining by rememberSaveable { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp || LocalDensity.current.fontScale > 1.4f
        val artSize = if (compact) 88.dp else minOf(maxWidth - 32.dp, maxHeight * 0.48f, 320.dp).coerceAtLeast(88.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (compact) Row(verticalAlignment = Alignment.CenterVertically) {
                TrackArt(snapshot.artworkUri, snapshot.artwork, Modifier.size(artSize))
                Column(Modifier.weight(1f).padding(start = 12.dp)) { NowPlayingIdentity(snapshot) }
            } else {
                TrackArt(snapshot.artworkUri, snapshot.artwork, Modifier.size(artSize))
                Spacer(Modifier.height(12.dp))
                NowPlayingIdentity(snapshot)
            }
            Text(when (snapshot.phase) {
                AudioPhase.PREPARING -> "Preparing audio…"
                AudioPhase.ENDED -> "Playback finished · tap play to replay"
                AudioPhase.ERROR -> "Playback needs attention"
                AudioPhase.PAUSED -> "Paused"
                AudioPhase.CONNECTING -> "Connecting…"
                AudioPhase.IDLE -> if (snapshot.title.isBlank()) "Choose a track to begin" else "Stopped"
                AudioPhase.PLAYING -> "Playing"
            }, style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            PlaybackControls(snapshot, controller)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (snapshot.podcast) {
                    PlayerActionIcon(Icons.Default.Replay, "Rewind 15 seconds", enabled = controller != null,
                        onClick = { controller?.seekTo((snapshot.positionMs - 15_000).coerceAtLeast(0)) })
                    PlayerActionIcon(Icons.Default.Forward30, "Forward 30 seconds", enabled = controller != null,
                        onClick = { controller?.seekTo((snapshot.positionMs + 30_000).coerceAtMost(if (snapshot.durationMs > 0) snapshot.durationMs else Long.MAX_VALUE)) })
                    Box {
                        PlayerActionIcon(Icons.Default.Speed, "Playback speed", state = "${snapshot.speed} times", enabled = controller != null,
                            selected = snapshot.speed != 1f, onClick = { speedMenu = true })
                        DropdownMenu(speedMenu, { speedMenu = false }) {
                            listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { speed ->
                                DropdownMenuItem(text = { Text("${speed}×") }, onClick = { controller?.setPlaybackSpeed(speed); speedMenu = false })
                            }
                        }
                    }
                    PlayerActionIcon(Icons.Default.Timer, "Toggle elapsed or remaining time", selected = remaining,
                        state = if (remaining) "Remaining time" else "Elapsed time", onClick = { remaining = !remaining })
                    PlayerActionIcon(if (played) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        if (played) "Mark episode unplayed" else "Mark episode played", selected = played,
                        state = if (played) "Played" else "Unplayed", onClick = { onPlayed(!played) })
                } else {
                    PlayerActionIcon(Icons.Default.Shuffle, "Toggle shuffle", enabled = controller != null && snapshot.queue.isNotEmpty(),
                        selected = snapshot.shuffle, state = if (snapshot.shuffle) "On" else "Off",
                        onClick = { controller?.shuffleModeEnabled = !snapshot.shuffle })
                    PlayerActionIcon(if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Change repeat mode", enabled = controller != null && snapshot.queue.isNotEmpty(), selected = snapshot.repeatMode != Player.REPEAT_MODE_OFF,
                        state = when (snapshot.repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Off" },
                        onClick = { controller?.repeatMode = when (snapshot.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        } })
                }
                PlayerActionIcon(Icons.AutoMirrored.Filled.QueueMusic, "Open playback queue", state = "${snapshot.queue.size} tracks", onClick = onQueue)
            }
            if (snapshot.podcast) Text("${snapshot.speed}× · " + if (remaining) "−${formatDuration((snapshot.durationMs - snapshot.positionMs).coerceAtLeast(0))} remaining" else "${formatDuration(snapshot.positionMs)} elapsed",
                style = BonyType.caption.copy(color = BonyColors.TextMute))
            if (snapshot.origin.isNotBlank()) Text(snapshot.origin, style = BonyType.caption.copy(color = BonyColors.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact visual icons with full-size touch targets and spoken state. */
@Composable
private fun PlayerActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String,
    state: String = "", selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).semantics {
        contentDescription = label
        if (state.isNotBlank()) stateDescription = state
    }) {
        Icon(icon, contentDescription = null, tint = when {
            !enabled -> BonyColors.TextMute.copy(alpha = 0.45f)
            selected -> BonyColors.Accent
            else -> BonyColors.TextMute
        })
    }
}

@Composable
private fun NowPlayingIdentity(snapshot: PlaybackSnapshot) {
    Text(snapshot.title.ifBlank { "Your audio" }, style = BonyType.title.copy(color = BonyColors.Text), maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(snapshot.artist.ifBlank { if (snapshot.title.isBlank()) "Choose a track in Library or Discover" else "Unknown artist" },
        style = BonyType.body.copy(color = BonyColors.TextMute), maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (snapshot.album.isNotBlank()) Text(snapshot.album, style = BonyType.caption.copy(color = BonyColors.TextMute), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private val LocalLoadAudioMetadata = androidx.compose.runtime.staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

private fun AudioTrack.withMetadata(metadata: Map<String, social.tbone.media.audio.AudioMetadataReader.Metadata>): AudioTrack {
    val data = metadata[url] ?: return this
    return copy(title = data.title?.takeIf { it.isNotBlank() } ?: title,
        artist = data.artist?.takeIf { it.isNotBlank() } ?: artist,
        album = data.album?.takeIf { it.isNotBlank() } ?: album, artworkBytes = data.artwork)
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AudioQueueSheet(snapshot: PlaybackSnapshot, controller: MediaController?, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BonyColors.Surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.8f).padding(horizontal = 16.dp)) {
            Text("Playback queue", style = BonyType.title.copy(color = BonyColors.Text))
            Text(snapshot.origin.ifBlank { "No active queue" }, style = BonyType.caption.copy(color = BonyColors.TextMute))
            if (snapshot.queue.isEmpty()) Text("Queue is empty. Play a track or a playlist to begin.", modifier = Modifier.padding(vertical = 24.dp), color = BonyColors.TextMute)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                items(snapshot.queue, key = { it.id + ":" + it.index }) { entry ->
                    fun indexNow(): Int = controller?.let { p -> (0 until p.mediaItemCount).firstOrNull { p.getMediaItemAt(it).mediaId == entry.id } } ?: -1
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text((if (entry.index == snapshot.index) "▶ " else "") + entry.title,
                            color = if (entry.index == snapshot.index) BonyColors.Accent else BonyColors.Text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { val index = indexNow(); if (index >= 0) { controller?.seekToDefaultPosition(index); controller?.prepare(); controller?.play() } })
                        Text(entry.artist.ifBlank { "Unknown artist" }, style = BonyType.caption.copy(color = BonyColors.TextMute))
                        androidx.compose.foundation.layout.FlowRow {
                            TextButton(enabled = controller != null && entry.index > 0, onClick = { val index = indexNow(); audioQueueMoveTarget(index, controller?.mediaItemCount ?: 0, -1)?.let { controller?.moveMediaItem(index, it) } }) { Text("Move up") }
                            TextButton(enabled = controller != null && entry.index < snapshot.queue.lastIndex, onClick = { val index = indexNow(); audioQueueMoveTarget(index, controller?.mediaItemCount ?: 0, 1)?.let { controller?.moveMediaItem(index, it) } }) { Text("Move down") }
                            TextButton(enabled = controller != null, onClick = { val index = indexNow(); if (index >= 0) controller?.removeMediaItem(index) }) { Text("Remove") }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun AudioTrackAboutDialog(track: AudioTrack, profile: ProfileContent?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("About this track") },
        text = { Column {
            Text(track.title)
            Text("Artist: ${track.artist}")
            Text("Album / show: ${track.album.ifBlank { "Unknown" }}")
            Text("Shared by: ${profile?.bestName ?: track.poster}")
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

private fun shareAudioPost(context: android.content.Context, event: Event) {
    val uri = "nostr:" + social.tbone.nostr.Nip19.hexToNevent(event.id, event.pubkey, event.kind)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, uri)
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share original post")) }
}
