package social.tbone.ui.hashtag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import social.tbone.nostr.EventKind
import social.tbone.nostr.quotedEventId
import social.tbone.ui.feed.NoteCard
import social.tbone.ui.feed.extractInlineQuoteId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HashtagFeedScreen(
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (tag: String) -> Unit = {},
    viewModel: HashtagFeedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()
    val unresolvedQuoteIds by viewModel.unresolvedQuoteIds.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("#${viewModel.hashtag}") },
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
            when {
                uiState.isLoading && uiState.events.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.events.isEmpty() -> {
                    Text(
                        text = "No notes for #${viewModel.hashtag} yet.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    CompositionLocalProvider(
                        social.tbone.ui.feed.LocalQuoteState provides
                            social.tbone.ui.feed.QuoteState(unresolvedQuoteIds),
                    ) {
                    LazyColumn(modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 28.dp)) {
                        items(uiState.events, key = { it.id }) { event ->
                            val refId = remember(event.id) {
                                if (event.kind == EventKind.REPOST) {
                                    event.parsedTags.firstOrNull { it.name == "e" }?.value()
                                } else {
                                    event.parsedTags.quotedEventId
                                        ?: extractInlineQuoteId(event.content)
                                }
                            }
                            NoteCard(
                                event = event,
                                profile = profiles[event.pubkey],
                                profiles = profiles,
                                quotedEvent = refId?.let { quotedEvents[it] },
                                quotedEvents = quotedEvents,
                                quotedEventId = refId,
                                onThreadClick = onThreadClick,
                                onProfileClick = onProfileClick,
                                onHashtagClick = onHashtagClick,
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}
