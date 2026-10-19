package social.tbone.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import dev.youniversal.theme.YouniversalTheme
import social.tbone.nostr.Event
import social.tbone.nostr.quotedEventId
import social.tbone.nostr.relay.RelayStatus
import social.tbone.ui.components.AccountSwitcherSheet
import social.tbone.ui.components.BonyBottomBar
import social.tbone.ui.components.BottomTab
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape

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
    val pendingEventScroll by viewModel.pendingEventScroll.collectAsStateWithLifecycle()
    val currentFeed by viewModel.currentFeed.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val quotedEvents by viewModel.quotedEvents.collectAsStateWithLifecycle()
    val unresolvedQuoteIds by viewModel.unresolvedQuoteIds.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val replies by viewModel.replies.collectAsStateWithLifecycle()
    val repliedByMe by viewModel.repliedByMe.collectAsStateWithLifecycle()
    val notifUnread by viewModel.notifUnread.collectAsStateWithLifecycle()
    val updateAvailable by viewModel.updateAvailable.collectAsStateWithLifecycle()
    val globalAutoLoadEnabled by viewModel.globalAutoLoadEnabled.collectAsStateWithLifecycle()
    val globalAutoLoadInterval by viewModel.globalAutoLoadIntervalMinutes.collectAsStateWithLifecycle()
    val pollVoteCounts by viewModel.pollVoteCounts.collectAsStateWithLifecycle()
    val pollTotalVotes by viewModel.pollTotalVotes.collectAsStateWithLifecycle()
    val pollMyVotes by viewModel.pollMyVotes.collectAsStateWithLifecycle()
    val pollVoteVersion by viewModel.pollVoteVersion.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var isRefreshing by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }
    var showGlobalIntervalMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.scrollToTop.collect { listState.scrollToItem(0) }
    }
    LaunchedEffect(currentFeed, globalAutoLoadEnabled, globalAutoLoadInterval) {
        if (currentFeed == FeedTab.GLOBAL && globalAutoLoadEnabled) {
            while (true) {
                delay(globalAutoLoadInterval.coerceIn(1, 20) * 60_000L)
                viewModel.loadNewerGlobal()
            }
        }
    }
    LaunchedEffect(pendingEventScroll, uiState.events) {
        val eventId = pendingEventScroll ?: return@LaunchedEffect
        val index = uiState.events.indexOfFirst { it.id == eventId }
        if (index >= 0) {
            listState.animateScrollToItem(index + 1) // index 0 is the geohash strip
            viewModel.consumePendingEventScroll(eventId)
        }
    }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalQuoteState provides QuoteState(unresolvedQuoteIds)) {
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
            onSettingsClick = onSettingsClick,
        )

        // ── Account row: avatar + name open the profile; ↕ switches accounts ──
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

        // Global controls: new events are buffered and loaded in batches, so a
        // live relay stream cannot continuously evict the last row while reading.
        if (currentFeed == FeedTab.GLOBAL) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("●", style = BonyType.meta.copy(color = BonyColors.Accent))
                    Spacer(Modifier.width(6.dp))
                    Text("GLOBAL", style = BonyType.tag.copy(color = BonyColors.Accent))
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${uiState.events.size} loaded",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                    if (uiState.globalNewerAvailable > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${uiState.globalNewerAvailable} new",
                            style = BonyType.meta.copy(color = BonyColors.Accent),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = globalAutoLoadEnabled,
                        onCheckedChange = viewModel::setGlobalAutoLoadEnabled,
                    )
                    Text(
                        text = "auto-load",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Box {
                        Text(
                            text = "${globalAutoLoadInterval} min  ▾",
                            style = BonyType.tag.copy(color = BonyColors.Accent),
                            modifier = Modifier
                                .background(BonyColors.Bg, RoundedCornerShape(16.dp))
                                .border(1.dp, BonyColors.RuleStrong, RoundedCornerShape(16.dp))
                                .clickable { showGlobalIntervalMenu = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        DropdownMenu(
                            expanded = showGlobalIntervalMenu,
                            onDismissRequest = { showGlobalIntervalMenu = false },
                        ) {
                            (1..20).forEach { minutes ->
                                DropdownMenuItem(
                                    text = { Text("every $minutes minute${if (minutes == 1) "" else "s"}") },
                                    onClick = {
                                        viewModel.setGlobalAutoLoadIntervalMinutes(minutes)
                                        showGlobalIntervalMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = when {
                            uiState.globalNewerLoading -> "loading…"
                            uiState.globalNewerAvailable > 0 -> "load new notes →"
                            else -> "check now →"
                        },
                        style = BonyType.tag.copy(
                            color = if (uiState.globalNewerLoading || uiState.globalOlderLoading)
                                BonyColors.TextMute else BonyColors.Accent,
                        ),
                        modifier = Modifier
                            .clickable(
                                enabled = !uiState.globalNewerLoading && !uiState.globalOlderLoading,
                                onClick = viewModel::loadNewerGlobal,
                            )
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                    )
                }
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
                                reactors = if (event.kind == social.tbone.nostr.EventKind.REPOST)
                                    quotedEvent?.let { reactions[it.id] }
                                else
                                    reactions[event.id],
                                replies = replies,
                                repliedByMe = repliedByMe,
                                pollVoteCounts = pollVoteCounts,
                                pollTotalVotes = pollTotalVotes,
                                pollMyVotes = pollMyVotes,
                                pollVoteVersion = pollVoteVersion,
                                onPollVote = viewModel::voteOnPoll,
                                activePubkey = activeAccount?.pubkey,
                                quotedEvents = quotedEvents,
                                quotedEventId = refId,
                            )
                        }
                        item(key = "feed_paging") {
                            if (currentFeed == FeedTab.GLOBAL) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(
                                            enabled = uiState.globalCanLoadOlder && !uiState.globalOlderLoading,
                                            onClick = viewModel::loadOlderGlobal,
                                        )
                                        .padding(vertical = 18.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (uiState.globalOlderLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = BonyColors.Accent,
                                            strokeWidth = 1.5.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = when {
                                            uiState.globalOlderLoading -> "loading 100 older notes…"
                                            uiState.globalCanLoadOlder -> "load 100 older notes"
                                            else -> "no more older notes available"
                                        },
                                        style = BonyType.caption.copy(
                                            color = if (uiState.globalCanLoadOlder) BonyColors.Accent else BonyColors.TextMute,
                                        ),
                                    )
                                }
                            } else {
                                Text(
                                    text = "─── pull to load older ───",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Bottom tab bar ───────────────────────────────────────────────────
        BonyBottomBar(
            selected = if (currentFeed == FeedTab.HOME) BottomTab.HOME else null,
            hasNotifications = notifUnread,
            hasUpdate = updateAvailable,
            onSelect = { tab ->
                when (tab) {
                    BottomTab.HOME -> viewModel.switchFeed(FeedTab.HOME)
                    BottomTab.COMPOSE -> onComposeClick()
                    BottomTab.NOTIF -> onNotificationsClick()
                    BottomTab.TOOLS -> onToolboxClick()
                    BottomTab.CONF -> onSettingsClick()
                }
            },
        )
    }

        // ── Brightness popup: tap anywhere outside the slider to dismiss ──────
        if (showBrightness) {
            BrightnessPopup(onDismiss = { showBrightness = false })
        }
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
        val brightnessCardShape = appCornerShape(MaterialTheme.shapes.medium)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(brightnessCardShape)
                .border(1.dp, BonyColors.RuleStrong, brightnessCardShape)
                .background(BonyColors.Surface, brightnessCardShape)
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
    onSettingsClick: () -> Unit,
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
        // The wordmark opens Settings.
        Text(
            text = "t-bone",
            style = BonyType.wordmark.copy(color = BonyColors.Text),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onSettingsClick),
        )

        // Brightness control (between the wordmark and the relays display).
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onBrightnessClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "◐",
                style = BonyType.title.copy(color = BonyColors.TextMute),
            )
        }
        Spacer(Modifier.width(4.dp))

        if (totalCount > 0) {
            Text(
                text = "relays $connectedCount/$totalCount",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { onRelayManagementClick() },
            )
            Spacer(Modifier.width(10.dp))
        }

        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable { onSearchClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⌕",
                style = BonyType.title.copy(color = BonyColors.TextMute),
            )
        }

        Spacer(Modifier.width(8.dp))

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

    val pillShape = appCornerShape(MaterialTheme.shapes.extraLarge)
    Box(
        modifier = Modifier
            .clip(pillShape)
            .border(1.dp, borderColor, pillShape)
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
    val youniversal = YouniversalTheme.enabled
    val effectiveFontScale = LocalDensity.current.fontScale.coerceAtLeast(0.1f)
    // Youniversal composes its text scale with Android's system scale. Cap the
    // rendered tab-label size so large accessibility/theme scales cannot force
    // either half-width tab outside the screen.
    val youniversalLabelSize =
        (11f * effectiveFontScale).coerceAtMost(15f).div(effectiveFontScale).sp
    val youniversalLetterSpacing = (0.4f / effectiveFontScale).sp
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Surface),
        ) {
            FeedTab.values().forEach { tab ->
                val active = currentFeed == tab
                val label = if (tab == FeedTab.HOME) "FOLLOWING" else "GLOBAL"
                val tabShape = appCornerShape(MaterialTheme.shapes.small, RectangleShape)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(tabShape)
                        .clickable { onSwitch(tab) }
                        .background(BonyColors.Surface, tabShape)
                        .then(if (active) Modifier.border(
                            width = 1.dp, color = BonyColors.Accent,
                            shape = tabShape,
                        ) else Modifier)
                        .heightIn(min = 46.dp)
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label,
                        style = BonyType.tag.copy(
                            fontSize = if (youniversal) youniversalLabelSize else BonyType.tag.fontSize,
                            letterSpacing = if (youniversal) youniversalLetterSpacing else BonyType.tag.letterSpacing,
                            color = if (active) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
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
