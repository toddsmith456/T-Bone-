package social.tbone.ui.feed

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.relay.RelayStatus
import social.tbone.ui.components.AccountSwitcherSheet
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onThreadClick: (eventId: String) -> Unit = {},
    onComposeClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (tag: String) -> Unit = {},
    onRelayManagementClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onToolboxClick: () -> Unit = {},
    onOpenGeohash: (String) -> Unit = {},
    onReplyClick: (Event) -> Unit = {},
    onQuoteClick: (Event) -> Unit = {},
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentFeed by viewModel.currentFeed.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val repliedByMe by viewModel.repliedByMe.collectAsStateWithLifecycle()
    val notifUnread by viewModel.notifUnread.collectAsStateWithLifecycle()
    val pollVoteCounts by viewModel.pollVoteCounts.collectAsStateWithLifecycle()
    val pollMyVotes by viewModel.pollMyVotes.collectAsStateWithLifecycle()
    val pollVoteVersion by viewModel.pollVoteVersion.collectAsStateWithLifecycle()

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

    val listState = rememberLazyListState()
    var isRefreshing by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scrollToTop.collect { listState.scrollToItem(0) }
    }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        // Relay statuses and tor state are collected inside FeedTopBar so their
        // frequent updates only recompose that small row, not the entire screen.
        FeedTopBar(
            viewModel = viewModel,
            onRelayManagementClick = onRelayManagementClick,
            onSearchClick = onSearchClick,
            onBrightnessClick = { showBrightness = true },
        )

        // ── Account switcher row ──────────────────────────────────────────────
        AccountSwitcherSheet(
            activeAccount = activeAccount,
            accounts = accounts,
            onSwitch = viewModel::switchAccount,
            onProfileClick = activeAccount?.let { { onProfileClick(it.pubkey) } },
            profiles = profiles,
        )

        // ── Feed tab strip ───────────────────────────────────────────────────
        FeedTabStrip(
            currentFeed = currentFeed,
            onSwitch = viewModel::switchFeed,
        )

        // Global live status row
        if (currentFeed == FeedTab.GLOBAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "●",
                    style = BonyType.meta.copy(color = BonyColors.Accent),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "live · streaming kind-1",
                    style = BonyType.meta.copy(color = BonyColors.Accent),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${uiState.events.size} events",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }

        // ── Feed content ─────────────────────────────────────────────────────
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true; viewModel.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                uiState.isLoading && uiState.events.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                    }
                }
                uiState.events.isEmpty() && !isRefreshing -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "no notes yet",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    }
                }
                else -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        // Compact horizontally-scrollable strip of home-pinned
                        // location channels with unread badges.
                        item(key = "geohash_home") {
                            GeohashHomeStrip(
                                onOpenGeohash = onOpenGeohash,
                            )
                        }
                        items(uiState.events, key = { it.id }) { event ->
                            val refId = remember(event.id) {
                                when (event.kind) {
                                    social.tbone.nostr.EventKind.REPOST ->
                                        event.parsedTags.firstOrNull { it.name == "e" }?.value()
                                    else -> event.parsedTags.quotedEventId
                                        ?: extractInlineQuoteId(event.content)
                                }
                            }
                            val quotedEvent = refId?.let { quotedEvents[it] }
                            NoteCard(
                                event = event,
                                profile = profiles[event.pubkey],
                                profiles = profiles,
                                quotedEvent = quotedEvent,
                                onThreadClick = onThreadClick,
                                onProfileClick = onProfileClick,
                                onHashtagClick = onHashtagClick,
                                onReply = onReplyClick,
                                onBoost = viewModel::boost,
                                onQuote = onQuoteClick,
                                onLike = viewModel::react,
                                onShare = onShare,
                                reactors = if (event.kind == social.tbone.nostr.EventKind.REPOST)
                                    quotedEvent?.let { reactions[it.id] }
                                else
                                    reactions[event.id],
                                replies = replies,
                                repliedByMe = repliedByMe,
                                pollVoteCounts = pollVoteCounts,
                                pollMyVotes = pollMyVotes,
                                pollVoteVersion = pollVoteVersion,
                                onPollVote = viewModel::voteOnPoll,
                                activePubkey = activeAccount?.pubkey,
                            )
                        }
                        item {
                            Text(
                                text = "─── pull to load older ───",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom tab bar ───────────────────────────────────────────────────
        BonyTabBar(
            currentFeed = currentFeed,
            hasNotifications = notifUnread,
            onHome = { viewModel.switchFeed(FeedTab.HOME) },
            onCompose = onComposeClick,
            onNotifications = onNotificationsClick,
            onToolbox = onToolboxClick,
            onSettings = onSettingsClick,
        )
    }

        // ── Brightness popup: tap anywhere outside the slider to dismiss ──────
        if (showBrightness) {
            BrightnessPopup(onDismiss = { showBrightness = false })
        }
    }
}


/** Popup with a brightness slider; tapping the scrim anywhere dismisses it. */
@Composable
private fun BrightnessPopup(onDismiss: () -> Unit) {
    val brightness by social.tbone.ui.media.ScreenBrightness.level.collectAsStateWithLifecycle()

    // Full-screen scrim — tap anywhere outside the slider to dismiss.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        // Slider card
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {} // swallow taps on the card
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "◐ brightness",
                style = BonyType.tag.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = brightness,
                onValueChange = { social.tbone.ui.media.ScreenBrightness.set(it) },
                valueRange = social.tbone.ui.media.ScreenBrightness.MIN..social.tbone.ui.media.ScreenBrightness.MAX,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (brightness < social.tbone.ui.media.ScreenBrightness.NORMAL) "dimmer"
                       else if (brightness > social.tbone.ui.media.ScreenBrightness.NORMAL) "brighter"
                       else "normal",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
    }
}

// ── Feed top bar ─────────────────────────────────────────────────────────────
//
// Collects relayStatuses and torEnabled here rather than in FeedScreen so that
// their frequent updates (every relay connect/disconnect) only invalidate this
// small row and never touch the LazyColumn below.

@Composable
private fun FeedTopBar(
    viewModel: FeedViewModel,
    onRelayManagementClick: () -> Unit,
    onSearchClick: () -> Unit,
    onBrightnessClick: () -> Unit,
) {
    val relayStatuses by viewModel.relayStatuses.collectAsStateWithLifecycle()
    val torEnabled by viewModel.torEnabled.collectAsStateWithLifecycle()
    val connectedCount = relayStatuses.values.count { it == RelayStatus.CONNECTED }
    val totalCount = relayStatuses.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "t-bone",
            style = BonyType.wordmark.copy(color = BonyColors.Text),
            modifier = Modifier.weight(1f),
        )

        // Brightness control (between the profile row above and the relays display).
        Text(
            text = "◐",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier.clickable(onClick = onBrightnessClick),
        )
        Spacer(Modifier.width(12.dp))

        if (totalCount > 0) {
            Text(
                text = "relays $connectedCount/$totalCount",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { onRelayManagementClick() },
            )
            Spacer(Modifier.width(10.dp))
        }

        Text(
            text = "⌕",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier.clickable { onSearchClick() },
        )

        Spacer(Modifier.width(12.dp))

        TorPill(
            torEnabled = torEnabled,
            connected = connectedCount > 0,
            onClick = onRelayManagementClick,
        )
    }
}

// ── Tor pill ─────────────────────────────────────────────────────────────────

@Composable
private fun TorPill(torEnabled: Boolean, connected: Boolean, onClick: () -> Unit) {
    val (label, color, borderColor) = if (torEnabled)
        Triple("◉ TOR", BonyColors.Warn, BonyColors.WarnDim)
    else if (connected)
        Triple("○ CLEAR", BonyColors.TextMute, BonyColors.Rule)
    else
        Triple("○ OFFLINE", BonyColors.Danger, BonyColors.Danger)

    Box(
        modifier = Modifier
            .border(1.dp, borderColor)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = BonyType.tag.copy(color = color),
        )
    }
}

// ── Feed tab strip ────────────────────────────────────────────────────────────

@Composable
private fun FeedTabStrip(
    currentFeed: FeedTab,
    onSwitch: (FeedTab) -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Surface),
        ) {
            FeedTab.values().forEach { tab ->
                val active = currentFeed == tab
                val label = if (tab == FeedTab.HOME) "FOLLOWING" else "GLOBAL"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSwitch(tab) }
                        .background(BonyColors.Surface)
                        .then(if (active) Modifier.border(
                            width = 1.dp, color = BonyColors.Accent,
                            shape = RectangleShape,
                        ) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = BonyType.tag.copy(
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }
        // Full-width bottom hairline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BonyColors.Rule)
                .align(Alignment.BottomCenter),
        )
    }
}

// ── Bottom navigation bar ─────────────────────────────────────────────────────

private data class TabItem(
    val glyph: String,
    val label: String,
    val icon: ImageVector? = null,
)

@Composable
fun BonyTabBar(
    currentFeed: FeedTab,
    hasNotifications: Boolean = false,
    onHome: () -> Unit,
    onCompose: () -> Unit,
    onNotifications: () -> Unit,
    onToolbox: () -> Unit,
    onSettings: () -> Unit,
) {
    val tabs = listOf(
        TabItem("", "HOME", icon = Icons.Outlined.Home) to onHome,
        TabItem("", "WRITE", icon = Icons.Outlined.Edit) to onCompose,
        TabItem("", "NOTIF", icon = Icons.Filled.Notifications) to onNotifications,
        TabItem("", "TOOLS", icon = Icons.Outlined.Construction) to onToolbox,
        TabItem("", "CONF", icon = Icons.Outlined.Settings) to onSettings,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Surface),
    ) {
        // Top hairline
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            tabs.forEachIndexed { i, (item, action) ->
                val isActive = i == 0 && currentFeed == FeedTab.HOME
                val contentColor = if (isActive) BonyColors.Accent else BonyColors.TextMute

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { action() }
                        .then(
                            if (isActive) Modifier.border(
                                width = 1.dp, color = BonyColors.Accent,
                                shape = RectangleShape,
                            ) else Modifier
                        )
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (item.icon != null) {
                        // Bell tab shows a small dot when there are unread notifications.
                        val showDot = hasNotifications && item.icon == Icons.Filled.Notifications
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label,
                                tint = contentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            if (showDot) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(6.dp)
                                        .background(BonyColors.Accent, CircleShape),
                                )
                            }
                        }
                    } else {
                        Text(
                            text = item.glyph,
                            style = BonyType.title.copy(
                                color = contentColor,
                                fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
                            ),
                        )
                    }
                    Text(
                        text = item.label,
                        style = BonyType.caption.copy(color = contentColor),
                    )
                }
            }
        }
    }
}
