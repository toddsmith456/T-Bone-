package social.tbone.ui.thread

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.EventKind
import social.tbone.nostr.Nip19
import social.tbone.nostr.quotedEventId
import social.tbone.ui.feed.NoteCard
import social.tbone.ui.feed.extractInlineQuoteId
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Thread screen.
 *
 * Renders the flattened conversation tree produced by [ThreadTree]: the root,
 * every note between it and the tapped note (fetched automatically — the old
 * "replies in between" placeholder is gone), then the replies as nested mini
 * threads with indent rails and "+N replies" folds.
 */
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
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText)
                    },
                    "Share note",
                ),
            )
        }
    }

    val items = uiState.items

    // Scroll to whichever note was opened once it is on screen.
    LaunchedEffect(uiState.focusedEventId, items.size) {
        val index = items.indexOfFirst {
            it is ThreadItem.Note && it.event.id == uiState.focusedEventId
        }
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
                if (uiState.replyCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${uiState.replyCount} ${if (uiState.replyCount == 1) "reply" else "replies"}",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                if (uiState.loadingContext) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "loading…",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
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
            uiState.notFound && items.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "this note was not found on any relay",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    itemsIndexed(
                        items = items,
                        key = { _, item -> item.key },
                        contentType = { _, item -> item::class.simpleName ?: "item" },
                    ) { index, item ->
                        when (item) {
                            is ThreadItem.Note -> ThreadRow(
                                item = item,
                                previousDepth = items.getOrNull(index - 1)?.depth ?: -1,
                                uiState = uiState,
                                profiles = profiles,
                                quotedEvents = quotedEvents,
                                reactions = reactions,
                                replies = replies,
                                repliedByMe = repliedByMe,
                                pollVoteCounts = pollVoteCounts,
                                pollMyVotes = pollMyVotes,
                                pollVoteVersion = pollVoteVersion,
                                activePubkey = activePubkey,
                                onThreadClick = onThreadClick,
                                onProfileClick = onProfileClick,
                                onHashtagClick = onHashtagClick,
                                onReplyClick = onReplyClick,
                                onQuoteClick = onQuoteClick,
                                onShare = onShare,
                                onBoost = viewModel::boost,
                                onLike = viewModel::react,
                                onPollVote = viewModel::voteOnPoll,
                                onExpandBranch = viewModel::expandBranch,
                                onCollapseBranch = viewModel::collapseBranch,
                            )

                            is ThreadItem.FoldedReplies -> FoldRow(
                                label = "＋ ${item.hiddenCount} " +
                                    if (item.hiddenCount == 1) "reply" else "replies",
                                depth = item.depth,
                                onClick = { viewModel.expandBranch(item.anchorId) },
                            )

                            is ThreadItem.ShowMoreReplies -> FoldRow(
                                label = "＋ show ${item.hiddenCount} more " +
                                    if (item.hiddenCount == 1) "reply" else "replies",
                                depth = item.depth,
                                onClick = { viewModel.expandFanOut(item.parentId) },
                            )
                        }
                    }

                    item(key = "thread_dead_end") {
                        ThreadDeadEnd(uiState.replyCount)
                    }
                }
            }
        }
    }
}

/**
 * One note plus its indent rail. The rail is drawn behind the card: a vertical
 * guide at each depth level, with a rounded corner turning into this row. Rails
 * that have no same-depth row directly above start dashed, so they read as a
 * continuation rather than a line appearing out of nowhere.
 */
@Composable
private fun ThreadRow(
    item: ThreadItem.Note,
    /** Depth of the row rendered above this one (-1 for the first row). */
    previousDepth: Int,
    uiState: ThreadUiState,
    profiles: Map<String, social.tbone.nostr.ProfileContent>,
    quotedEvents: Map<String, Event>,
    reactions: Map<String, Set<String>>,
    replies: Map<String, Int>,
    repliedByMe: Set<String>,
    pollVoteCounts: Map<String, Map<String, Int>>,
    pollMyVotes: Map<String, List<String>>,
    pollVoteVersion: Int,
    activePubkey: String?,
    onThreadClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onHashtagClick: (String) -> Unit,
    onReplyClick: (Event) -> Unit,
    onQuoteClick: (Event) -> Unit,
    onShare: (Event) -> Unit,
    onBoost: (Event) -> Unit,
    onLike: (Event) -> Unit,
    onPollVote: (Event, List<String>) -> Unit,
    onExpandBranch: (String) -> Unit,
    onCollapseBranch: (String) -> Unit,
) {
    val event = item.event
    val depth = item.depth.coerceAtMost(ThreadIndent.MAX_RAIL_DEPTH)
    val quotedEvent = remember(event.id, quotedEvents) {
        val refId = when (event.kind) {
            EventKind.REPOST -> event.parsedTags.firstOrNull { it.name == "e" }?.value()
            else -> event.parsedTags.quotedEventId ?: extractInlineQuoteId(event.content)
        }
        refId?.let { quotedEvents[it] }
    }
    // The note you opened and the thread root are shown in full; replies stay
    // compact so the thread remains scannable.
    val isThreadRoot = event.id == uiState.root?.id
    val hidden = item.descendantCount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .threadRail(
                depth = depth,
                previousDepth = previousDepth,
                dashedCorner = item.connectorStartsMidAir,
            ),
    ) {
        NoteCard(
            event = event,
            profile = profiles[event.pubkey],
            profiles = profiles,
            highlighted = item.highlighted,
            fullContent = isThreadRoot || item.highlighted,
            showReplyContext = false,
            quotedEvent = quotedEvent,
            onThreadClick = onThreadClick,
            onProfileClick = onProfileClick,
            onHashtagClick = onHashtagClick,
            onReply = onReplyClick,
            onBoost = onBoost,
            onQuote = onQuoteClick,
            onLike = onLike,
            onShare = onShare,
            reactors = if (event.kind == EventKind.REPOST) quotedEvent?.let { reactions[it.id] }
            else reactions[event.id],
            replies = replies,
            repliedByMe = repliedByMe,
            pollVoteCounts = pollVoteCounts,
            pollMyVotes = pollMyVotes,
            pollVoteVersion = pollVoteVersion,
            onPollVote = onPollVote,
            activePubkey = activePubkey,
        )

        if (hidden > 0) {
            // Toggle the subtree: "+N replies" opens it, the count closes it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp + ThreadIndent.STEP * (depth + 1), end = 14.dp)
                    .padding(bottom = 6.dp)
                    .clickable {
                        if (item.folded) onExpandBranch(event.id) else onCollapseBranch(event.id)
                    },
            ) {
                Text(
                    text = if (item.folded) {
                        "＋ $hidden " + if (hidden == 1) "reply" else "replies"
                    } else {
                        "▾ $hidden " + if (hidden == 1) "reply" else "replies"
                    },
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        }
    }
}

/** "no more replies" terminator with a rail stub, like the reference app. */
@Composable
private fun ThreadDeadEnd(replyCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, top = 6.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(22.dp)
                .background(BonyColors.Rule),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (replyCount == 0) "no replies yet" else "end of thread",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
    }
}

@Composable
private fun FoldRow(label: String, depth: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp + ThreadIndent.STEP * depth, end = 14.dp, bottom = 10.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BonyType.tag.copy(color = BonyColors.Accent),
        )
    }
}

/** Indent geometry shared by the rail drawing and the row padding. */
internal object ThreadIndent {
    val STEP = 14.dp
    val START = 22.dp
    const val MAX_RAIL_DEPTH = 3
}

/**
 * Draws the thread rails behind a row.
 *
 * For a row at depth `d`, rails are drawn at every level `0..d-1`, plus a
 * rounded corner at level `d-1` turning into the row.
 */
private fun Modifier.threadRail(
    depth: Int,
    previousDepth: Int,
    dashedCorner: Boolean,
): Modifier {
    if (depth <= 0) return this
    return this.drawBehind {
        val stroke = 1.5.dp.toPx()
        val step = ThreadIndent.STEP.toPx()
        val start = ThreadIndent.START.toPx()
        val color = BonyColors.Rule
        val h = size.height
        val cornerRadius = 7.dp.toPx()
        val dash = PathEffect.dashPathEffect(
            floatArrayOf(3.dp.toPx(), 4.dp.toPx()), 0f,
        )

        for (level in 0 until depth) {
            val x = start + level * step
            val isLast = level == depth - 1
            // A rail continues from the row above only when that row also drew
            // a rail at this level (i.e. it sat deeper than this level). If not,
            // the rail starts in mid-air and is dashed at the top.
            val dashedTop = if (isLast) dashedCorner else previousDepth < level + 1
            if (!isLast) {
                // Pass-through rail: half-step short of the bottom so the lines
                // read as a continuous ladder instead of touching.
                if (dashedTop) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, h - 4.dp.toPx()),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                        pathEffect = dash,
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, h - 4.dp.toPx()),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                // Corner rail: straight down then curving right into the row.
                val bottom = h - 4.dp.toPx()
                if (dashedTop) {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, bottom - cornerRadius),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                        pathEffect = dash,
                    )
                } else {
                    drawLine(
                        color = color,
                        start = Offset(x, 0f),
                        end = Offset(x, bottom - cornerRadius),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
                // Quarter arc turning the vertical rail into the note row:
                // tangent to the rail at (x, bottom - r) and horizontal at
                // (x + r, bottom).
                drawArc(
                    color = color,
                    startAngle = 180f,
                    sweepAngle = -90f,
                    useCenter = false,
                    topLeft = Offset(x, bottom - cornerRadius * 2),
                    size = Size(cornerRadius * 2, cornerRadius * 2),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}
