package social.tbone.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.ui.feed.NoteMediaContent
import social.tbone.ui.feed.parseEventContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onProfileClick: (pubkey: String) -> Unit,
    onHashtagClick: (tag: String) -> Unit,
    initialQuery: String = "",
    autoOpenEvent: Boolean = false,
    onOpenEventInFeed: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    var autoOpened by remember(initialQuery, autoOpenEvent) { mutableStateOf(false) }

    LaunchedEffect(initialQuery) {
        if (initialQuery.isNotBlank()) viewModel.onQueryChange(initialQuery)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(uiState, autoOpenEvent) {
        val state = uiState as? SearchUiState.EventInput
        val event = state?.event
        if (autoOpenEvent && !autoOpened && event != null) {
            autoOpened = true
            viewModel.openInMainFeed(event)
            onOpenEventInFeed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("nevent / note, npub, #hashtag, or name…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val state = uiState) {
                is SearchUiState.Idle -> {
                    Text(
                        text = "Search profiles by name, paste an npub or note/nevent, or browse a #hashtag",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                }

                is SearchUiState.Searching -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is SearchUiState.EventInput -> {
                    when {
                        state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        state.event == null -> Text(
                            text = state.error ?: "Event not found.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                        else -> EventSearchResult(
                            event = state.event,
                            onProfileClick = onProfileClick,
                            onOpenInFeed = {
                                viewModel.openInMainFeed(state.event)
                                onOpenEventInFeed()
                            },
                        )
                    }
                }

                is SearchUiState.NpubInput -> {
                    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 28.dp)) {
                        item {
                            ProfileRow(
                                pubkey = state.pubkey,
                                displayName = state.profile?.bestName,
                                pictureUrl = state.profile?.picture,
                                nip05 = state.profile?.nip05,
                                onClick = { onProfileClick(state.pubkey) },
                            )
                            if (state.profile == null) {
                                Text(
                                    text = "Fetching profile metadata from relays…  tap to open profile",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }

                is SearchUiState.HashtagInput -> {
                    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 28.dp)) {
                        item {
                            HashtagRow(
                                tag = state.tag,
                                onClick = { onHashtagClick(state.tag) },
                            )
                        }
                    }
                }

                is SearchUiState.Results -> {
                    if (state.profiles.isEmpty() && state.isSearching) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Searching profiles…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    } else if (state.profiles.isEmpty()) {
                        Text(
                            text = "No results",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                            contentPadding = PaddingValues(bottom = 28.dp),
                        ) {
                            if (state.isSearching) {
                                item(key = "profile-search-progress") {
                                    Text(
                                        text = "Searching relay for more matches…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            items(state.profiles, key = { it.pubkey }) { match ->
                                ProfileRow(
                                    pubkey = match.pubkey,
                                    displayName = match.profile?.bestName,
                                    pictureUrl = match.profile?.picture,
                                    nip05 = match.profile?.nip05,
                                    onClick = { onProfileClick(match.pubkey) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventSearchResult(
    event: Event,
    onProfileClick: (String) -> Unit,
    onOpenInFeed: () -> Unit,
) {
    val parsed = remember(event.id, event.content, event.tags) { parseEventContent(event) }
    val nevent = remember(event.id, event.pubkey, event.kind) {
        runCatching { Nip19.hexToNevent(event.id, event.pubkey, event.kind) }.getOrNull()
            ?: Nip19.hexToNote(event.id)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("NOTE / EVENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Text(
                    text = nevent,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = "by ${event.pubkey.take(16)}…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onProfileClick(event.pubkey) },
                )
                Spacer(Modifier.size(12.dp))
                if (parsed.text.isNotBlank()) {
                    Text(parsed.text, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.size(12.dp))
                }
                if (parsed.mediaItems.isNotEmpty()) {
                    NoteMediaContent(parsed.mediaItems, Modifier.fillMaxWidth())
                    Spacer(Modifier.size(12.dp))
                }
                TextButton(onClick = onOpenInFeed, modifier = Modifier.fillMaxWidth()) {
                    Text("OPEN IN MAIN FEED")
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(
    pubkey: String,
    displayName: String?,
    pictureUrl: String?,
    nip05: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (pictureUrl != null) {
            AsyncImage(
                model = pictureUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (displayName != null) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val npub = Nip19.hexToNpub(pubkey)
            Text(
                text = if (nip05 != null) "✓ $nip05" else "${npub.take(12)}…${npub.takeLast(8)}",
                style = MaterialTheme.typography.labelSmall,
                color = if (nip05 != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = if (nip05 == null) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HashtagRow(tag: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Tag,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp).padding(8.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "#$tag",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Browse hashtag feed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
