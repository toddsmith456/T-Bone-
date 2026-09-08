package social.tbone.ui.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.notifications.FlatNotifItem
import social.tbone.notifications.NotifFilter
import social.tbone.notifications.NotifSummary
import social.tbone.notifications.NotifType
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.feed.QuotedNoteCard
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onThreadClick: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onReplyClick: (Event) -> Unit = {},
    onOpenCalendarEvent: (String) -> Unit = {},
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val enabledFilters by viewModel.enabledFilters.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val referencedNotes by viewModel.referencedNotes.collectAsStateWithLifecycle()
    val hasUnread by viewModel.hasUnread.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Only one row expanded at a time.
    var expandedId by remember { mutableStateOf<String?>(null) }
    var showFilterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.markRead() }
    LaunchedEffect(hasUnread) { if (hasUnread) viewModel.markRead() }

    val allEnabled = enabledFilters.size == NotifFilter.entries.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "notifications",
                            style = BonyType.body.copy(color = BonyColors.Text),
                        )
                        Text(
                            text = "  |  24h",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Filter notifications",
                            tint = if (allEnabled) BonyColors.TextMute else BonyColors.Accent,
                        )
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // The 24h summary bar is ALWAYS visible — even when a selected
                // filter (e.g. mentions) currently has zero items, the stat
                // stays up so you can see at a glance what's empty.
                DailySummaryBar(
                    summary = summary,
                    calendarCount = items.count { it.type == NotifType.CALENDAR },
                    enabledFilters = enabledFilters,
                    onFilterSelect = viewModel::isolateType,
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                when {
                    isLoading && items.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                        }
                    }
                    items.isEmpty() && !isRefreshing -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "no notifications yet",
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                            )
                        }
                    }
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                    ) {
                    items(items, key = { it.id }) { item ->
                        val isExpanded = expandedId == item.id
                        NotificationRow(
                            item = item,
                            profile = profiles[item.actorPubkey],
                            profiles = profiles,
                            onOpenCalendarEvent = onOpenCalendarEvent,
                            // For replies the referenced card is the parent note;
                            // for everything else it is the note reacted/reposted/mentioned.
                            referencedNote = if (item.type == NotifType.REPLY)
                                item.referencedEventId?.let { referencedNotes[it] }
                            else
                                item.note ?: item.referencedEventId?.let { referencedNotes[it] },
                            isExpanded = isExpanded,
                            onToggle = { expandedId = if (isExpanded) null else item.id },
                            onOpenThread = onThreadClick,
                            onOpenProfile = onProfileClick,
                            onReply = onReplyClick,
                        )
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                    }
                    // Load older notifications on demand (older than the 24h cache).
                    item(key = "load_more") {
                        LoadMoreBar(
                            hasMore = hasMore,
                            loadingMore = loadingMore,
                            onLoadMore = viewModel::loadMore,
                        )
                    }
                        // Buffer so the system action bar never covers the last row.
                        item(key = "bottom_buffer") { Spacer(Modifier.height(32.dp)) }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        NotificationFilterSheet(
            enabledFilters = enabledFilters,
            onToggleType = viewModel::toggleType,
            onEnableAll = viewModel::enableAll,
            onDisableAll = viewModel::disableAll,
            onDismiss = { showFilterSheet = false },
        )
    }
}

/** "Load older notifications" bar at the bottom of the list. */
@Composable
private fun LoadMoreBar(
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Text(
                text = "loading…",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            hasMore -> Text(
                text = "load older",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .border(1.dp, BonyColors.AccentDim, RectangleShape)
                    .clickable(onClick = onLoadMore)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            else -> Text(
                text = "— no older notifications —",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
    }
}

// ── Filter sheet (mirrors Wisp's NotificationFilterSheet) ────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationFilterSheet(
    enabledFilters: Set<NotifFilter>,
    onToggleType: (NotifFilter) -> Unit,
    onEnableAll: () -> Unit,
    onDisableAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = BonyColors.Surface) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text(
                text = "notification types",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = BonyColors.Rule)

            NotifFilter.entries.forEach { filter ->
                val enabled = filter in enabledFilters
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleType(filter) }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = filter.icon(),
                        contentDescription = null,
                        tint = if (enabled) BonyColors.Accent else BonyColors.TextMute,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = filter.label,
                        style = BonyType.body.copy(
                            color = if (enabled) BonyColors.Text else BonyColors.TextMute,
                        ),
                    )
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggleType(filter) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BonyColors.Bg,
                            checkedTrackColor = BonyColors.Accent,
                            uncheckedThumbColor = BonyColors.TextMute,
                            uncheckedTrackColor = BonyColors.Rule,
                        ),
                    )
                }
            }

            HorizontalDivider(color = BonyColors.Rule)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(onClick = onEnableAll) {
                    Text("ENABLE ALL", style = BonyType.tag.copy(color = BonyColors.Accent))
                }
                TextButton(onClick = onDisableAll) {
                    Text("DISABLE ALL", style = BonyType.tag.copy(color = BonyColors.TextMute))
                }
            }
        }
    }
}

private fun NotifFilter.icon(): ImageVector = when (this) {
    NotifFilter.REPLIES -> Icons.Outlined.ChatBubbleOutline
    NotifFilter.REACTIONS -> Icons.Outlined.FavoriteBorder
    NotifFilter.REPOSTS -> Icons.Outlined.Repeat
    NotifFilter.MENTIONS -> Icons.Outlined.AlternateEmail
    NotifFilter.VOTES -> Icons.Outlined.BarChart
    NotifFilter.CALENDAR -> Icons.Outlined.CalendarMonth
}

// ── 24h summary bar ──────────────────────────────────────────────────────────

@Composable
private fun DailySummaryBar(
    summary: NotifSummary,
    calendarCount: Int,
    enabledFilters: Set<NotifFilter>,
    onFilterSelect: (NotifFilter) -> Unit,
) {
    val isFiltered = enabledFilters.size == 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Surface)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryStat(
            icon = Icons.Outlined.ChatBubbleOutline,
            value = summary.replyCount.toString(),
            active = isFiltered && NotifFilter.REPLIES in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.REPLIES) },
        )
        SummaryStat(
            icon = Icons.Outlined.FavoriteBorder,
            value = summary.reactionCount.toString(),
            active = isFiltered && NotifFilter.REACTIONS in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.REACTIONS) },
        )
        SummaryStat(
            icon = Icons.Outlined.Repeat,
            value = summary.repostCount.toString(),
            active = isFiltered && NotifFilter.REPOSTS in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.REPOSTS) },
        )
        SummaryStat(
            icon = Icons.Outlined.AlternateEmail,
            value = (summary.mentionCount + summary.quoteCount).toString(),
            active = isFiltered && NotifFilter.MENTIONS in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.MENTIONS) },
        )
        SummaryStat(
            icon = Icons.Outlined.BarChart,
            value = summary.voteCount.toString(),
            active = isFiltered && NotifFilter.VOTES in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.VOTES) },
        )
        SummaryStat(
            icon = Icons.Outlined.CalendarMonth,
            value = calendarCount.toString(),
            active = isFiltered && NotifFilter.CALENDAR in enabledFilters,
            onClick = { onFilterSelect(NotifFilter.CALENDAR) },
        )
    }
}

@Composable
private fun SummaryStat(
    icon: ImageVector,
    value: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (active) BonyColors.Accent else BonyColors.TextMute
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(onClick = onClick)
            .then(
                if (active) Modifier.border(1.dp, BonyColors.AccentDim, RectangleShape)
                else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(5.dp))
        Text(text = value, style = BonyType.meta.copy(color = tint))
    }
}

// ── Notification row ─────────────────────────────────────────────────────────

@Composable
private fun NotificationRow(
    item: FlatNotifItem,
    profile: ProfileContent?,
    profiles: Map<String, ProfileContent>,
    referencedNote: Event?,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onOpenThread: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onReply: (Event) -> Unit,
    onOpenCalendarEvent: (String) -> Unit,
) {
    // Calendar events render like any other notification — icon, title, time —
    // but tap straight into the event instead of a thread/profile.
    if (item.type == NotifType.CALENDAR) {
        CalendarNotifRow(item, onOpenCalendarEvent)
        return
    }

    val actorName = profile?.bestName ?: item.actorPubkey.phraseHandle()

    Column(modifier = Modifier.fillMaxWidth()) {
        // Compact row — always visible.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reactions show their emoji next to the name, not as a leading icon.
            if (item.type != NotifType.REACTION) {
                NotifTypeIcon(item)
                Spacer(Modifier.width(10.dp))
            }
            UserAvatar(
                pubkeyHex = item.actorPubkey,
                profile = profile,
                size = 32.dp,
                modifier = Modifier.clickable { onOpenProfile(item.actorPubkey) },
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.type == NotifType.REACTION) {
                        Text(
                            text = item.emoji ?: "♥",
                            style = BonyType.body.copy(color = BonyColors.Text),
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = actorName,
                        style = BonyType.bodyDim.copy(color = BonyColors.Text),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = actionText(item),
                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = remember(item.timestamp) { item.timestamp.formatNotifTimestamp() },
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }

        // Expanded section — just reveal the note the notification is about.
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            when (item.type) {
                NotifType.CALENDAR -> Unit
                NotifType.REPLY -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Reply graphic header.
                        Text(
                            text = "↳ replied to",
                            style = BonyType.tag.copy(color = BonyColors.Accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 2.dp, bottom = 4.dp),
                        )
                        // The original note being replied to — on top. If it
                        // hasn't loaded yet, show a hint; tapping still opens
                        // the thread.
                        if (referencedNote != null) {
                            QuotedNoteCard(
                                event = referencedNote,
                                profile = profiles[referencedNote.pubkey],
                                profiles = profiles,
                                onThreadClick = onOpenThread,
                                onProfileClick = onOpenProfile,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        } else if (item.referencedEventId != null) {
                            Text(
                                text = "↳ loading parent note…",
                                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                                modifier = Modifier
                                    .padding(start = 16.dp, top = 2.dp, bottom = 4.dp)
                                    .clickable { item.referencedEventId?.let(onOpenThread) },
                            )
                        }
                        // The reply itself, underneath the original.
                        item.note?.let { reply ->
                            QuotedNoteCard(
                                event = reply,
                                profile = profiles[reply.pubkey],
                                profiles = profiles,
                                onThreadClick = onOpenThread,
                                onProfileClick = onOpenProfile,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        ReplyAction(item.note) { onReply(it) }
                    }
                }
                NotifType.VOTE -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Voted option labels (like Wisp).
                        val pollOptions = remember(referencedNote?.id) {
                            referencedNote?.let { social.tbone.nostr.Nip88.parsePollOptions(it) }.orEmpty()
                        }
                        if (item.voteOptionIds.isNotEmpty() && pollOptions.isNotEmpty()) {
                            val labels = item.voteOptionIds.mapNotNull { id ->
                                pollOptions.firstOrNull { it.id == id }?.label
                            }
                            if (labels.isNotEmpty()) {
                                Text(
                                    text = "voted: " + labels.joinToString(", "),
                                    style = BonyType.meta.copy(color = BonyColors.Accent),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 56.dp, top = 2.dp, bottom = 4.dp),
                                )
                            }
                        }
                        // The poll itself.
                        val target = referencedNote ?: item.note
                        if (target != null) {
                            QuotedNoteCard(
                                event = target,
                                profile = profiles[target.pubkey],
                                profiles = profiles,
                                onThreadClick = onOpenThread,
                                onProfileClick = onOpenProfile,
                                modifier = Modifier.padding(start = 56.dp, end = 14.dp, bottom = 10.dp),
                            )
                        } else {
                            Text(
                                text = "loading…",
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                                modifier = Modifier.padding(start = 56.dp, end = 14.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
                NotifType.MENTION, NotifType.QUOTE -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        item.note?.let { note ->
                            QuotedNoteCard(
                                event = note,
                                profile = profiles[note.pubkey],
                                profiles = profiles,
                                onThreadClick = onOpenThread,
                                onProfileClick = onOpenProfile,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        ReplyAction(item.note) { onReply(it) }
                    }
                }
                NotifType.REACTION, NotifType.REPOST -> {
                    // Just reveal the note that was reacted to / reposted.
                    val target = referencedNote ?: item.note
                    if (target != null) {
                        QuotedNoteCard(
                            event = target,
                            profile = profiles[target.pubkey],
                            profiles = profiles,
                            onThreadClick = onOpenThread,
                            onProfileClick = onOpenProfile,
                            modifier = Modifier.padding(start = 56.dp, end = 14.dp, bottom = 10.dp),
                        )
                    } else {
                        Text(
                            text = "loading…",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                            modifier = Modifier.padding(start = 56.dp, end = 14.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Small inline "reply" affordance at the bottom of an expanded note. */
@Composable
private fun ReplyAction(note: Event?, onReply: (Event) -> Unit) {
    if (note == null) return
    Text(
        text = "↳ reply",
        style = BonyType.tag.copy(color = BonyColors.Accent),
        modifier = Modifier
            .padding(start = 16.dp, top = 4.dp, bottom = 10.dp)
            .clickable { onReply(note) },
    )
}

/** The leading slot: a type icon (reactions render their emoji inline instead). */
@Composable
private fun NotifTypeIcon(item: FlatNotifItem) {
    Box(
        modifier = Modifier.size(26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (item.type) {
            NotifType.REACTION -> Unit
            NotifType.REPLY -> Icon(
                Icons.Outlined.ChatBubbleOutline, "reply", Modifier.size(18.dp), BonyColors.TextMute,
            )
            NotifType.REPOST -> Icon(
                Icons.Outlined.Repeat, "repost", Modifier.size(18.dp), BonyColors.TextMute,
            )
            NotifType.MENTION -> Icon(
                Icons.Outlined.AlternateEmail, "mention", Modifier.size(18.dp), BonyColors.TextMute,
            )
            NotifType.QUOTE -> Icon(
                Icons.Outlined.FormatQuote, "quote", Modifier.size(18.dp), BonyColors.TextMute,
            )
            NotifType.VOTE -> Icon(
                Icons.Outlined.BarChart, "vote", Modifier.size(18.dp), BonyColors.TextMute,
            )
            NotifType.CALENDAR -> Icon(
                Icons.Outlined.CalendarMonth, "calendar", Modifier.size(18.dp), BonyColors.Accent,
            )
        }
    }
}

private fun actionText(item: FlatNotifItem): String = when (item.type) {
    NotifType.REACTION -> "reacted"
    NotifType.REPOST -> "reposted"
    NotifType.REPLY -> "replied"
    NotifType.QUOTE -> "quoted"
    NotifType.MENTION -> "mentioned you"
    NotifType.VOTE -> "voted"
    NotifType.CALENDAR -> "upcoming"
}

/** A calendar event row — same look and feel as the other notification rows. */
@Composable
private fun CalendarNotifRow(
    item: FlatNotifItem,
    onOpenCalendarEvent: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                item.calendarEventId?.let(onOpenCalendarEvent)
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NotifTypeIcon(item)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.calendarTitle ?: "calendar event",
                style = BonyType.body.copy(color = BonyColors.Accent),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "at " + remember(item.timestamp) {
                    java.time.Instant.ofEpochMilli(item.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a · MMM d"))
                },
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = remember(item.timestamp) { item.timestamp.formatNotifTimestamp() },
            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
        )
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Short 3-word phrase handle fallback for when there's no display name. */
private fun String.phraseHandle(): String {
    val words = Phrase.wordsFor(this, 3)
    return if (words.all { it == "·····" }) {
        val npub = Nip19.hexToNpub(this)
        "${npub.take(9)}…"
    } else {
        words.joinToString("·")
    }
}

/** Wisp-style relative timestamps: s/m/h/d, then a date. */
private fun Long.formatNotifTimestamp(): String {
    val now = System.currentTimeMillis() / 1000
    val delta = now - this
    return when {
        delta < 0 -> Instant.ofEpochSecond(this).formatNotifDate()
        delta < 60 -> "${delta}s"
        delta < 3600 -> "${delta / 60}m"
        delta < 86400 -> "${delta / 3600}h"
        delta < 604800 -> "${delta / 86400}d"
        else -> Instant.ofEpochSecond(this).formatNotifDate()
    }
}

private fun Instant.formatNotifDate(): String {
    val now = Instant.now()
    val thisYear = this.atZone(ZoneId.systemDefault()).year
    val nowYear = now.atZone(ZoneId.systemDefault()).year
    val pattern = if (thisYear != nowYear) "MMM d, yyyy" else "MMM d"
    return this.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
}
