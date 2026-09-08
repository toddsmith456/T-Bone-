package social.tbone.ui.thread

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.quotedEventId
import social.tbone.ui.components.DotAvatar
import social.tbone.ui.feed.NoteCard
import social.tbone.ui.feed.extractInlineQuoteId
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    onProfileClick: (pubkey: String) -> Unit = {},
    onThreadClick: (eventId: String) -> Unit = {},
    onHashtagClick: (tag: String) -> Unit = {},
    onReplyClick: (Event) -> Unit = {},
    onQuoteClick: (Event) -> Unit = {},
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val repliedByMe by viewModel.repliedByMe.collectAsStateWithLifecycle()
    val pollVoteCounts by viewModel.pollVoteCounts.collectAsStateWithLifecycle()
    val pollMyVotes by viewModel.pollMyVotes.collectAsStateWithLifecycle()
    val pollVoteVersion by viewModel.pollVoteVersion.collectAsStateWithLifecycle()
    val activePubkey by viewModel.activePubkey.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val onShare = remember {
        { event: Event ->
            val noteUri = "nostr:${Nip19.hexToNote(event.id)}"
            val shareText = buildString {
                val text = event.content.take(280).trim()
                if (text.isNotEmpty()) { append(text); append("\n\n") }
                append(noteUri)
            }
            context.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) },
                "Share note",
            ))
        }
    }

    val items = buildList {
        uiState.root?.let { add(ThreadItem.Note(it)) }
        if (uiState.showGap) add(ThreadItem.Gap)
        uiState.parent?.let { add(ThreadItem.Note(it)) }
        uiState.focused?.let { add(ThreadItem.Note(it, focused = true)) }
        if (uiState.replies.isNotEmpty()) add(ThreadItem.LiveRepliesHeader(uiState.replies.size))
        uiState.replies.forEach { add(ThreadItem.Note(it)) }
    }

    LaunchedEffect(items.size, uiState.focusedEventId) {
        val index = items.indexOfFirst { it is ThreadItem.Note && it.event.id == uiState.focusedEventId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Bg)
                .statusBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "←",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { onBack() },
                )
                Spacer(Modifier.width(8.dp))
                Text("thread", style = BonyType.body.copy(color = BonyColors.Text))
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }

        // ── Content ───────────────────────────────────────────────────────────
        when {
            uiState.isLoading && items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                        when (item) {
                            is ThreadItem.Note -> {
                                val quotedEvent = remember(item.event.id, quotedEvents) {
                                    val refId = when (item.event.kind) {
                                        EventKind.REPOST ->
                                            item.event.parsedTags.firstOrNull { it.name == "e" }?.value()
                                        else -> item.event.parsedTags.quotedEventId
                                            ?: extractInlineQuoteId(item.event.content)
                                    }
                                    refId?.let { quotedEvents[it] }
                                }
                                // The note you opened and the thread root are shown in
                                // full; replies stay compact so the thread stays scannable.
                                val isThreadRoot = item.event.id == uiState.root?.id
                                NoteCard(
                                    event = item.event,
                                    profile = profiles[item.event.pubkey],
                                    profiles = profiles,
                                    highlighted = item.focused,
                                    fullContent = isThreadRoot || item.focused,
                                    quotedEvent = quotedEvent,
                                    onThreadClick = onThreadClick,
                                    onProfileClick = onProfileClick,
                                    onHashtagClick = onHashtagClick,
                                    onReply = onReplyClick,
                                    onBoost = viewModel::boost,
                                    onQuote = onQuoteClick,
                                    onLike = viewModel::react,
                                    onShare = onShare,
                                    reactors = if (item.event.kind == EventKind.REPOST)
                                        quotedEvent?.let { reactions[it.id] }
                                    else
                                        reactions[item.event.id],
                                    replies = replies,
                                    repliedByMe = repliedByMe,
                                    pollVoteCounts = pollVoteCounts,
                                    pollMyVotes = pollMyVotes,
                                    pollVoteVersion = pollVoteVersion,
                                    onPollVote = viewModel::voteOnPoll,
                                    activePubkey = activePubkey,
                                )
                            }
                            ThreadItem.Gap -> GapIndicator()
                            is ThreadItem.LiveRepliesHeader -> LiveRepliesHeader(item.count)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GapIndicator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(BonyColors.Rule))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "replies in between",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f).height(1.dp).background(BonyColors.Rule))
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun LiveRepliesHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Surface)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("●", style = BonyType.meta.copy(color = BonyColors.Accent))
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$count LIVE ${if (count == 1) "REPLY" else "REPLIES"}",
            style = BonyType.tag.copy(color = BonyColors.Accent),
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

private sealed interface ThreadItem {
    val key: Any

    data class Note(val event: Event, val focused: Boolean = false) : ThreadItem {
        override val key get() = event.id
    }

    data object Gap : ThreadItem {
        override val key get() = "gap"
    }

    data class LiveRepliesHeader(val count: Int) : ThreadItem {
        override val key get() = "live_replies_header"
    }
}
