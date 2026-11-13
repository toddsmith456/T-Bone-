package social.tbone.toolbox.feeds

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.ui.feed.FeedTabIcons
import social.tbone.ui.feed.NoteCard
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

/**
 * The feed viewer: a strip of open feeds over a single timeline.
 *
 * It looks like the home screen because it is the same job — read a feed — but it
 * is a different thing underneath: the tabs here are throwaway, they can be any
 * feed type, and none of them touch the saved layout. Closing the tool leaves no
 * trace, which is the point of having it beside the home screen rather than
 * instead of it.
 */
@Composable
fun FeedViewerScreen(
    onBack: () -> Unit,
    onThreadClick: (eventId: String) -> Unit = {},
    onProfileClick: (pubkey: String) -> Unit = {},
    onHashtagClick: (tag: String) -> Unit = {},
    viewModel: FeedToolViewModel = hiltViewModel(),
) {
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()

    val active = tabs.firstOrNull { it.tabId == activeTabId }
    val listState = rememberLazyListState()
    var isRefreshing by remember { mutableStateOf(false) }
    // The launcher covers the viewer rather than being a second route: choosing a
    // feed must reach the view model that owns the tabs, and a route hop would
    // create a second view model that has no tabs in it.
    var picking by remember { mutableStateOf(tabs.isEmpty()) }

    LaunchedEffect(Unit) {
        viewModel.scrollToTop.collect { listState.animateScrollToItem(0) }
    }
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) isRefreshing = false
    }
    // Once a feed is open there is nothing left to pick, so the launcher closes
    // itself; this keeps every "open a feed" affordance pointing at one place.
    LaunchedEffect(tabs.size) {
        if (tabs.isNotEmpty()) picking = false
    }

    if (picking) {
        FeedViewerLauncher(
            onBack = {
                if (tabs.isEmpty()) onBack() else picking = false
            },
            onOpen = { source ->
                viewModel.open(source)
                picking = false
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = active?.source?.label?.lowercase() ?: "feed viewer",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        active == null -> "open a feed to read it"
                        state.isLoading -> "loading…"
                        state.blockedReason != null -> "cannot load this feed"
                        else -> "${state.events.size} notes"
                    },
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            Text(
                text = "+",
                style = BonyType.body.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.AccentDim)
                    .clickable { picking = true }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            if (tabs.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "close all",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable(viewModel::closeAll),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // ── The open feeds ───────────────────────────────────────────────────
        if (tabs.isNotEmpty()) {
            ViewerTabStrip(
                tabs = tabs,
                activeTabId = activeTabId,
                onSelect = viewModel::select,
                onClose = viewModel::close,
            )
        }

        // ── Timeline ─────────────────────────────────────────────────────────
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { isRefreshing = true; viewModel.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            when {
                active == null -> EmptyViewer(onOpenPicker = { picking = true })
                state.blockedReason != null -> CenteredMessage(
                    title = state.blockedReason.orEmpty(),
                    hint = "this feed needs something set up first — it is not a loading problem",
                )
                state.isLoading && state.events.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = BonyColors.Accent, strokeWidth = 1.dp)
                }
                state.events.isEmpty() -> CenteredMessage(
                    title = "nothing in this feed",
                    hint = "pull to refresh, or open a different feed",
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.events, key = { it.id }) { event ->
                        NoteCard(
                            event = event,
                            profile = profiles[event.pubkey],
                            profiles = profiles,
                            onThreadClick = onThreadClick,
                            onProfileClick = onProfileClick,
                            onHashtagClick = onHashtagClick,
                        )
                    }
                    item(key = "viewer_paging") {
                        if (state.canPageOlder) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        enabled = !state.loadingOlder,
                                        onClick = viewModel::loadOlder,
                                    )
                                    .padding(vertical = 18.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.loadingOlder) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = BonyColors.Accent,
                                        strokeWidth = 1.5.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = if (state.loadingOlder) "loading older…" else "load older notes",
                                    style = BonyType.caption.copy(color = BonyColors.Accent),
                                )
                            }
                        } else {
                            Text(
                                text = "─── end of this feed ───",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The viewer's own strip.
 *
 * Deliberately not the home screen's [social.tbone.ui.feed.FeedTabStrip]: these
 * tabs are closable, carry no saved identity, and scroll as soon as there are more
 * than will fit, because a viewer tab strip is a row of open documents rather
 * than a set of permanent destinations.
 */
@Composable
private fun ViewerTabStrip(
    tabs: List<FeedTabState>,
    activeTabId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
) {
    val scrollState = rememberScrollState()
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val needsScroll = tabs.size * 110 > screenWidth - 16

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab.tabId == activeTabId
            Row(
                modifier = Modifier
                    .appBorder(1.dp, if (active) BonyColors.Accent else BonyColors.Rule)
                    .background(BonyColors.Surface)
                    .clickable { onSelect(tab.tabId) }
                    .padding(start = 8.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = FeedTabIcons.forSource(tab.source),
                    contentDescription = null,
                    tint = if (active) BonyColors.Accent else BonyColors.TextMute,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = tab.source.label.lowercase(),
                    style = BonyType.tag.copy(
                        color = if (active) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                // A per-tab close, on the tab itself: a long-press or a swipe here
                // would be undiscoverable and would fight the strip's own scroll.
                Text(
                    text = "✕",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable { onClose(tab.tabId) }
                        .padding(horizontal = 3.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun EmptyViewer(onOpenPicker: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("no feed open", style = BonyType.body.copy(color = BonyColors.Text))
        Spacer(Modifier.height(8.dp))
        Text(
            text = "pick a feed type and it opens here as a tab — nothing is added to your home screen",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "open a feed →",
            style = BonyType.tag.copy(color = BonyColors.Accent),
            modifier = Modifier
                .appBorder(1.dp, BonyColors.AccentDim)
                .clickable { picking = true }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CenteredMessage(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = BonyType.meta.copy(color = BonyColors.Accent), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(hint, style = BonyType.caption.copy(color = BonyColors.TextMute), textAlign = TextAlign.Center)
    }
}
