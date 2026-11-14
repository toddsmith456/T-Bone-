package social.tbone.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.youniversal.theme.YouniversalTheme
import kotlinx.coroutines.delay
import social.tbone.nostr.Event
import social.tbone.nostr.quotedEventId
import social.tbone.settings.HideReason
import social.tbone.nostr.relay.RelayStatus
import social.tbone.ui.components.AccountSwitcherSheet
import social.tbone.ui.components.BonyBottomBar
import social.tbone.ui.components.LocalUiDeclutter
import social.tbone.ui.components.RelayHealthDot
import social.tbone.ui.components.BottomTab
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import dev.youniversal.theme.YouniversalEmptyState
import social.tbone.ui.theme.appCornerShape
import social.tbone.ui.theme.feedPageBackground
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

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
    val homeLayout by viewModel.homeLayout.collectAsStateWithLifecycle()
    val activeSlot by viewModel.activeSlot.collectAsStateWithLifecycle()
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
    val clockHours by viewModel.clockFeedHours.collectAsStateWithLifecycle()
    val pollVoteCounts by viewModel.pollVoteCounts.collectAsStateWithLifecycle()
    val pollTotalVotes by viewModel.pollTotalVotes.collectAsStateWithLifecycle()
    val pollMyVotes by viewModel.pollMyVotes.collectAsStateWithLifecycle()
    val pollVoteVersion by viewModel.pollVoteVersion.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.setFeedVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.setFeedVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        viewModel.setFeedVisible(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setFeedVisible(false)
        }
    }

    val listState = rememberLazyListState()
    val uiDeclutter = LocalUiDeclutter.current
    var isRefreshing by remember { mutableStateOf(false) }
    var showBrightness by remember { mutableStateOf(false) }
    var showGlobalIntervalMenu by remember { mutableStateOf(false) }

    LaunchedEffect(uiDeclutter) {
        if (uiDeclutter) showBrightness = false
    }

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
    LaunchedEffect(pendingEventScroll, uiState.feedItems) {
        val eventId = pendingEventScroll ?: return@LaunchedEffect
        val index = uiState.feedItems.indexOfFirst {
            it is FeedListItem.Note && it.event.id == eventId
        }
        if (index >= 0) {
            // +1 for the geohash home strip, which is always the first item.
            // The old hidden-count header that used to add a second offset is gone.
            listState.animateScrollToItem(index + 1)
            viewModel.consumePendingEventScroll(eventId)
        }
    }

    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) isRefreshing = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalQuoteState provides QuoteState(unresolvedQuoteIds, viewModel::retryQuote)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(feedPageBackground()),
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
            compact = uiDeclutter,
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
        // Rendered from the stored layout rather than from the FeedTab enum, so a
        // swapped, reordered or hidden feed shows up here with no other change.
        FeedTabStrip(
            slots = homeLayout.visibleSlots,
            activeSlotId = activeSlot?.id,
            onSelect = viewModel::switchToSlot,
            compact = uiDeclutter,
        )

        // ── Clock feed: the hour the timeline is anchored at ─────────────────
        if (currentFeed == FeedTab.CLOCK) {
            ClockHourStrip(
                selected = clockHours,
                anchor = uiState.clockAnchor,
                onSelect = viewModel::setClockFeedHours,
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }

        // ── Suggested feed: whose notes these are ────────────────────────────
        if (currentFeed == FeedTab.SUGGESTED) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FeedTabIcons.tbone,
                    contentDescription = null,
                    tint = BonyColors.Accent,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("T-BONE PICKS", style = BonyType.tag.copy(color = BonyColors.Accent))
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (uiState.suggestedAuthors > 0) {
                        "${uiState.suggestedAuthors} of ${uiState.suggestedTotal} accounts posting"
                    } else {
                        "${uiState.suggestedTotal} accounts"
                    },
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        }

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
                                .background(BonyColors.Bg, appCornerShape(MaterialTheme.shapes.medium, RoundedCornerShape(16.dp)))
                                .border(1.dp, BonyColors.RuleStrong, appCornerShape(MaterialTheme.shapes.medium, RoundedCornerShape(16.dp)))
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
                        if (YouniversalTheme.enabled) {
                            // Kit empty state: designed, centered, on-palette.
                            YouniversalEmptyState(
                                title = uiState.error ?: when (currentFeed) {
                                    FeedTab.CLOCK -> "nothing in this window"
                                    FeedTab.SUGGESTED -> "no notes from these accounts"
                                    else -> "no notes yet"
                                },
                                description = if (currentFeed == FeedTab.SUGGESTED) {
                                    "─── the end · pull to refresh ───"
                                } else {
                                    null
                                },
                            )
                            return@Box
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = uiState.error ?: when (currentFeed) {
                                    FeedTab.CLOCK -> "nothing in this window"
                                    FeedTab.SUGGESTED -> "no notes from these accounts"
                                    else -> "no notes yet"
                                },
                                style = BonyType.meta.copy(color = if (uiState.error != null) BonyColors.Warn else BonyColors.TextMute),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 22.dp),
                            )
                            if (currentFeed == FeedTab.SUGGESTED) {
                                Text(
                                    text = "─── the end · pull to refresh ───",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                )
                            } else if (currentFeed == FeedTab.GLOBAL) {
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
                                // Follows and Clock both load one page and stop.
                                Text(
                                    text = "─── pull to refresh ───",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                )
                            }
                        }
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
                        // The end-of-feed "N posts hidden by your filters" banner used
                        // to sit here, at the top of the timeline, on every visit. It is
                        // gone: the inline HiddenGroupRow markers still appear where the
                        // hidden notes actually were, so nothing is silently missing and
                        // there is no permanent notice to scroll past.
                        items(uiState.feedItems, key = { it.key }) { row ->
                          when (row) {
                            is FeedListItem.HiddenGroup -> HiddenGroupRow(
                                group = row,
                                profiles = profiles,
                            )
                            is FeedListItem.Note -> {
                            val event = row.event
                            val refId = remember(event.id) {
                                when (event.kind) {
                                    social.tbone.nostr.EventKind.REPOST,
                                    social.tbone.nostr.EventKind.GENERIC_REPOST ->
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
                                reactors = if (event.kind == social.tbone.nostr.EventKind.REPOST ||
                                    event.kind == social.tbone.nostr.EventKind.GENERIC_REPOST
                                )
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
                          }
                        }
                        item(key = "feed_paging") {
                            if (currentFeed == FeedTab.SUGGESTED) {
                                Text(
                                    text = "─── the end · pull to refresh ───",
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                )
                            } else if (currentFeed == FeedTab.GLOBAL) {
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
                                // Follows and Clock both load one page and stop.
                                Text(
                                    text = "─── pull to refresh ───",
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
    compact: Boolean,
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
            .padding(horizontal = 14.dp, vertical = if (compact) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            // The wordmark remains the classic shortcut to Settings.
            Text(
                text = "t-bone",
                style = BonyType.wordmark.copy(color = BonyColors.Text),
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onSettingsClick),
            )

            // Brightness control is hidden only in declutter mode.
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
        } else {
            Spacer(Modifier.weight(1f))
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

        Spacer(Modifier.width(if (compact) 0.dp else 8.dp))

        if (compact) {
            RelayHealthDot(
                connected = connectedCount,
                total = totalCount,
                onClick = onRelayManagementClick,
            )
        } else {
            TorPill(
                torEnabled = torEnabled,
                connected = connectedCount > 0,
                onClick = onRelayManagementClick,
            )
        }
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

/**
 * The home tab strip: a four-wide window that slides over however many feeds the
 * user has.
 *
 * ### Why a window rather than growing tabs
 * Adding a fifth feed used to squeeze five tabs into the width of four, which
 * looked like a bug — the labels shrank and the row stopped being a row. The
 * strip is therefore a fixed four-column track that can hold any number of tabs
 * and shows four at a time. The fifth sits immediately off the right edge, so a
 * swipe brings it on and pushes the first one off.
 *
 * ### Why snapping is done by the strip, not by Compose's fling
 * Paging with `animateScrollTo` after a fling is what makes a strip feel broken:
 * the fling competes with the animation and the row lands between two positions.
 * Instead the drag is consumed here and the offset is a plain, animated state.
 * The finger moves the row 1:1 for the first [dragResistance] fraction of a tab,
 * which is the friction the user asked for — a small nudge does nothing visible
 * except hint that there is another tab — and past that it snaps to the next
 * position as soon as the finger lifts. A flick is converted into exactly one
 * tab of movement, whatever its speed, so the row can never overshoot.
 */
/**
 * The home tab strip: a fixed four-column window with a single compact arrow on
 * whichever side still has feeds to reach.
 *
 * ### The rules this obeys
 *  - **Four at a time, always.** Never five squashed into four, never a partial
 *    tab peeking in at the edge. Five feeds means four on screen and one behind
 *    the arrow, not five columns.
 *  - **It never scrolls backwards past the first feed.** The window starts at
 *    index 0 and the left arrow only exists once you have moved right, so there
 *    is nothing to slide back into.
 *  - **The arrow costs nothing.** It is a margin-less column the width of a
 *    glyph, not a button with padding, so the four tabs keep the full width they
 *    had when there were only four. Appearing and disappearing shifts the track
 *    by exactly that width, which is why the tabs animate rather than jump.
 *
 * ### Why a sliding track instead of swapping which four are laid out
 * Re-laying out four different tabs makes them look like different items each
 * time the window moves. A single track translated by whole tab widths keeps the
 * identity of each tab continuous, so moving right reads as the row moving rather
 * than as four tabs being replaced.
 */
@Composable
private fun FeedTabStrip(
    slots: List<social.tbone.home.FeedSlot>,
    activeSlotId: String?,
    onSelect: (social.tbone.home.FeedSlot) -> Unit,
    compact: Boolean,
) {
    val youniversal = YouniversalTheme.enabled
    val effectiveFontScale = LocalDensity.current.fontScale.coerceAtLeast(0.1f)
    // Youniversal composes its text scale with Android's system scale. Cap the
    // rendered tab-label size so large accessibility/theme scales cannot force a
    // tab outside its slot.
    val labelDp = if (youniversal) (9f * effectiveFontScale).coerceAtMost(12f) else 9f
    val tabLabelSize = (labelDp / effectiveFontScale).sp
    val tabLetterSpacing = (0.2f / effectiveFontScale).sp

    val windowSize = VISIBLE_TABS
    val maxStart = (slots.size - windowSize).coerceAtLeast(0)

    // First tab of the window. Clamped on every read so a layout edit that
    // removes feeds cannot leave the window pointing past the end.
    var firstVisible by remember { mutableIntStateOf(0) }
    val start = firstVisible.coerceIn(0, maxStart)

    // Selecting a tab that is outside the window brings it into view. This is the
    // only thing that moves the window besides the arrows, and it is what makes a
    // layout edit (or the app restoring a remembered tab) land somewhere sane.
    LaunchedEffect(activeSlotId, slots.size) {
        val index = slots.indexOfFirst { it.id == activeSlotId }
        if (index < 0) return@LaunchedEffect
        val desired = when {
            index < start -> index
            index > start + windowSize - 1 -> index - windowSize + 1
            else -> start
        }
        if (desired != firstVisible) firstVisible = desired
    }

    val canGoLeft = start > 0
    val canGoRight = start < maxStart

    /** The feeds currently in the window — never more than four. */
    val window = slots.drop(start).take(windowSize)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .background(BonyColors.Surface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (slots.isEmpty()) {
                // Only possible if a stored layout failed to parse and was
                // replaced by defaults; the repository guarantees this cannot
                // happen, and this keeps the strip from collapsing to nothing.
                Text(
                    text = "no feeds",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            if (canGoLeft) {
                PageArrow("‹", "previous feeds") {
                    firstVisible = (start - 1).coerceAtLeast(0)
                }
            }
            // Only the four tabs in the window are composed. A wider, translated
            // track would draw four and lay out eight, and Compose's hit testing
            // over a clipped child is the kind of thing that produces a tab you
            // can see but cannot tap.
            AnimatedContent(
                targetState = start,
                transitionSpec = {
                    // A page left or right, clipped to the strip. `sizeTransform`
                    // is disabled so the strip cannot change height mid-slide.
                    val slide = tween<androidx.compose.ui.unit.IntOffset>(SLIDE_FADE_MS)
                    val fade = tween<Float>(SLIDE_FADE_MS)
                    if (targetState > initialState) {
                        (slideInHorizontally(slide) { width -> width } + fadeIn(fade))
                            .togetherWith(slideOutHorizontally(slide) { width -> -width } + fadeOut(fade))
                    } else {
                        (slideInHorizontally(slide) { width -> -width } + fadeIn(fade))
                            .togetherWith(slideOutHorizontally(slide) { width -> width } + fadeOut(fade))
                    }.using(SizeTransform(clip = true))
                },
                label = "feedTabWindow",
                modifier = Modifier.weight(1f).clipToBounds(),
            ) { _ ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    window.forEach { slot ->
                        FeedTabItem(
                            slot = slot,
                            active = slot.id == activeSlotId,
                            compact = compact,
                            labelSize = tabLabelSize,
                            letterSpacing = tabLetterSpacing,
                            modifier = Modifier.weight(1f),
                        ) { onSelect(slot) }
                    }
                }
            }
            if (canGoRight) {
                PageArrow("›", "more feeds") {
                    firstVisible = (start + 1).coerceAtMost(maxStart)
                }
            }
        }
        // Full-width bottom hairline, below the outer padding so it cannot
        // overlap the active tab's border.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BonyColors.Rule),
        )
    }
}

/**
 * The strip's page arrow.
 *
 * Deliberately tiny: no minimum touch target padding, no background, no border.
 * A 24 dp square would push the four tabs narrower than they are in a layout with
 * exactly four feeds, and the whole point is that adding a fifth feed costs
 * nothing visually. The glyph itself is 16 dp and the touch area is grown
 * vertically (not horizontally) to stay tappable without stealing width.
 */
@Composable
private fun PageArrow(glyph: String, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(16.dp)
            .height(52.dp)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = BonyType.body.copy(color = BonyColors.Accent),
            maxLines = 1,
        )
    }
}

/** A tab: glyph, optional label, and the active border. */
@Composable
private fun FeedTabItem(
    slot: social.tbone.home.FeedSlot,
    active: Boolean,
    compact: Boolean,
    labelSize: androidx.compose.ui.unit.TextUnit,
    letterSpacing: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tabShape = appCornerShape(MaterialTheme.shapes.small, RectangleShape)
    // Tint animates so switching feels like the same control moving, not
    // unrelated buttons lighting up.
    val tint by animateColorAsState(
        targetValue = if (active) BonyColors.Accent else BonyColors.TextMute,
        animationSpec = tween(160),
        label = "feedTabTint",
    )
    // The active border is drawn OVER the tab, not around it. As a border inside
    // the column it consumed layout: four bordered tabs summed to a fraction of a
    // pixel less than the row and the strip read as misaligned whenever a fifth
    // feed existed. As a full-size overlay the column is always exactly its share.
    Box(modifier = modifier.background(BonyColors.Surface, tabShape).clip(tabShape)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = if (compact) 36.dp else 50.dp)
                .padding(horizontal = if (compact) 8.dp else 2.dp, vertical = if (compact) 4.dp else 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = FeedTabIcons.forSource(slot.source),
                // The label is the accessible name; announcing both reads it twice.
                contentDescription = if (compact) slot.source.label else null,
                tint = tint,
                modifier = Modifier.size(17.dp),
            )
            if (!compact) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = slot.source.label,
                    style = BonyType.tag.copy(
                        fontSize = labelSize,
                        letterSpacing = letterSpacing,
                        color = tint,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        if (active) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .border(width = 1.dp, color = BonyColors.Accent, shape = tabShape),
            )
        }
    }
}

/** How many feeds the strip shows at once — always, whatever the layout holds. */
private const val VISIBLE_TABS = 4

/** One page of the strip sliding over. Matches the app's other nav transitions. */
private const val SLIDE_FADE_MS = 180

// ── Clock feed: hour selector ────────────────────────────────────────────────

/**
 * The clock feed's hour picker.
 *
 * Collapsed by default: one line showing the hour you chose, which is all you
 * need once it is set — a 24-chip row permanently parked under the tabs costs a
 * whole note's worth of vertical space. Tapping the line expands the scrollable
 * 1–24 h strip; picking an hour collapses it again.
 *
 * The selection persists (`AppSettings.clockFeedHours`), so the collapsed line
 * is also the state you get on the next launch.
 */
@Composable
private fun ClockHourStrip(
    selected: Int,
    anchor: Long,
    onSelect: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // When it opens, put the active hour in view without a scroll animation —
    // the row is appearing, not moving.
    LaunchedEffect(expanded, selected) {
        if (expanded) listState.scrollToItem((selected - 1 - 2).coerceAtLeast(0))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Surface)
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = FeedTabIcons.clock,
                contentDescription = null,
                tint = BonyColors.Accent,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${selected}h ago",
                style = BonyType.tag.copy(color = BonyColors.Accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "· ${clockTimeLabel(anchor)}",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.weight(1f))
            // The chevron is the only affordance the collapsed row needs.
            Text(
                text = if (expanded) "▴" else "▾",
                style = BonyType.tag.copy(color = BonyColors.TextMute),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(24) { index ->
                    val hours = index + 1
                    val isSelected = hours == selected
                    val shape = appCornerShape(MaterialTheme.shapes.small, RectangleShape)
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .border(
                                1.dp,
                                if (isSelected) BonyColors.Accent else BonyColors.Rule,
                                shape,
                            )
                            .background(
                                if (isSelected) BonyColors.AccentBg else BonyColors.Bg,
                                shape,
                            )
                            .clickable {
                                onSelect(hours)
                                expanded = false
                            }
                            .heightIn(min = 32.dp)
                            .padding(horizontal = 11.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${hours}h",
                            style = BonyType.tag.copy(
                                color = if (isSelected) BonyColors.Accent else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** "09:12" in the device's own timezone — the anchor, not "now". */
private fun clockTimeLabel(anchorSeconds: Long): String {
    if (anchorSeconds <= 0L) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = anchorSeconds * 1000L }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, m)
}

/** Collapsed marker for a run of notes removed by the content filters. */
@Composable
private fun HiddenGroupRow(
    group: FeedListItem.HiddenGroup,
    profiles: Map<String, social.tbone.nostr.ProfileContent?>,
) {
    var expanded by remember(group.key) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.Bg)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "${if (expanded) "▾" else "▸"} ${group.entries.size} post${if (group.entries.size == 1) "" else "s"} hidden by your filters",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
        )
        // Without a per-row reason the "hidden" label is ambiguous: a note hidden
        // because you blocked its author reads identically to one hidden because
        // it quotes someone you blocked. Expand to see which, per note.
        if (expanded) {
            group.entries.forEach { entry ->
                val author = profiles[entry.pubkey]?.bestName ?: "unknown author"
                // "blocked author" alone was misleading: it also covered notes by
                // people you had NOT blocked, whose post merely referenced a blocked
                // account — which is what made a followed author look blocked.
                val reason = when (val r = entry.reason) {
                    is HideReason.BlockedAuthor ->
                        if (r.direct) "you muted this author" else "references an author you muted"
                    is HideReason.HideWord -> "hide word '${r.word}'"
                    HideReason.ContentWarning -> "content warning (hide mode)"
                }
                Text(
                    text = "hidden · $author · $reason",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}
