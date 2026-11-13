package social.tbone.toolbox.feeds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.home.FeedSource
import social.tbone.ui.feed.FeedTabIcons
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

/**
 * The feed viewer's launcher: every feed type the app can read, each with the
 * controls that type needs, none of which require saving anything to the home
 * screen.
 *
 * The home editor answers "which four feeds do I want to keep?"; this answers
 * "show me this feed now". Keeping them apart is what stops the home layout from
 * filling up with experiments.
 *
 * Sections are ordered by how much input they need: the clock window and the two
 * app-wide feeds need nothing, then a tag or a relay address, then the two that
 * need a list of keys.
 */
@Composable
fun FeedViewerLauncher(
    onBack: () -> Unit,
    onOpen: (FeedSource) -> Unit,
    viewModel: FeedViewerLauncherViewModel = hiltViewModel(),
) {
    val saved by viewModel.savedFeeds.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<String?>(null) }
    var hours by remember { mutableIntStateOf(6) }
    var text by remember { mutableStateOf("") }
    var listName by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding(),
    ) {
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
                Text("feed viewer", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "read any feed without adding it to home",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            if (saved.isNotEmpty()) {
                Text(
                    text = "${saved.size} saved",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        notice?.let { message ->
            Text(
                text = message,
                style = BonyType.meta.copy(color = BonyColors.Warn),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { notice = null }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // ── Saved feeds, if any ──────────────────────────────────────────
            if (saved.isNotEmpty()) {
                item { SectionHeader("saved feeds", "from your home screen") }
                items(saved, key = { "saved-${it.hashCode()}-${it.label}" }) { source ->
                    FeedSourceRow(
                        source = source,
                        subtitle = savedSubtitle(source),
                        onClick = { onOpen(source) },
                    )
                }
            }

            // ── No setup needed ─────────────────────────────────────────────
            item { SectionHeader("now", "no setup") }
            item {
                FeedSourceRow(
                    source = FeedSource.Follows(),
                    subtitle = "everyone you follow, newest first",
                    onClick = { onOpen(FeedSource.Follows()) },
                )
            }
            item {
                ClockRow(
                    hours = hours,
                    expanded = expanded == "clock",
                    onExpand = { expanded = if (expanded == "clock") null else "clock" },
                    onHours = { hours = it },
                    onOpen = { onOpen(FeedSource.Clock(hours = hours)) },
                )
            }
            item {
                FeedSourceRow(
                    source = FeedSource.Suggested(),
                    subtitle = "the curated accounts the app ships with",
                    onClick = { onOpen(FeedSource.Suggested()) },
                )
            }
            item {
                FeedSourceRow(
                    source = FeedSource.Global(),
                    subtitle = "every relay in your global set — needs global relays enabled",
                    onClick = { onOpen(FeedSource.Global()) },
                )
            }

            // ── Needs one value ─────────────────────────────────────────────
            item { SectionHeader("by tag or relay", "one value") }
            item {
                InputRow(
                    source = FeedSource.Hashtag(tags = listOf("tag")),
                    title = "hashtag",
                    subtitle = "everything posted with a tag",
                    fieldLabel = "tag, e.g. bitcoin",
                    value = if (expanded == "tag") text else "",
                    expanded = expanded == "tag",
                    onExpand = {
                        expanded = if (expanded == "tag") null else "tag"
                        text = ""
                    },
                    onChange = { text = it },
                    onGo = {
                        val tag = text.trim().removePrefix("#").lowercase()
                        if (tag.isBlank()) {
                            notice = "type a tag to view"
                        } else {
                            expanded = null
                            onOpen(FeedSource.Hashtag(tags = listOf(tag)))
                        }
                    },
                )
            }
            item {
                InputRow(
                    source = FeedSource.Relay(urls = listOf("wss://relay")),
                    title = "relay",
                    subtitle = "one relay's own timeline, no follow list needed",
                    fieldLabel = "relay, e.g. wss://nostr.wine",
                    value = if (expanded == "relay") text else "",
                    expanded = expanded == "relay",
                    onExpand = {
                        expanded = if (expanded == "relay") null else "relay"
                        text = ""
                    },
                    onChange = { text = it },
                    onGo = {
                        val url = text.trim().let {
                            if (it.startsWith("ws://") || it.startsWith("wss://")) it else "wss://$it"
                        }
                        if (!social.tbone.nostr.relay.RelayPool.isValidRelayUrl(url)) {
                            notice = "that does not look like a relay address"
                        } else {
                            expanded = null
                            onOpen(FeedSource.Relay(urls = listOf(url)))
                        }
                    },
                )
            }

            // ── Needs a list ────────────────────────────────────────────────
            item { SectionHeader("by people", "paste keys") }
            item {
                PeopleRow(
                    expanded = expanded == "people",
                    onExpand = { expanded = if (expanded == "people") null else "people" },
                    name = listName,
                    onName = { listName = it },
                    keys = text,
                    onKeys = { text = it },
                    onGo = {
                        val parsed = viewModel.parseKeys(text)
                        if (parsed.isEmpty()) {
                            notice = "no npub or 64-character hex keys found in that"
                        } else {
                            val name = listName.trim().ifBlank { "people" }
                            expanded = null
                            listName = ""
                            text = ""
                            onOpen(FeedSource.People(name = name, pubkeys = parsed))
                        }
                    },
                )
            }
            item {
                FeedSourceRow(
                    source = FeedSource.Polls(),
                    subtitle = "polls from people you follow",
                    onClick = { onOpen(FeedSource.Polls()) },
                )
            }

            item {
                Text(
                    text = "the viewer keeps nothing. closing a tab drops its " +
                        "subscriptions and its notes; save a feed on the home screen to keep it.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, hint: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = BonyType.tag.copy(color = BonyColors.Accent))
        Spacer(Modifier.weight(1f))
        Text(hint, style = BonyType.caption.copy(color = BonyColors.TextMute))
    }
}

@Composable
private fun FeedSourceRow(
    source: FeedSource,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolGlyph(FeedTabIcons.forSource(source))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.label.lowercase(),
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("→", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun ToolGlyph(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .appBorder(1.dp, BonyColors.Rule)
            .background(BonyColors.SurfaceAlt),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = BonyColors.Accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun ClockRow(
    hours: Int,
    expanded: Boolean,
    onExpand: () -> Unit,
    onHours: (Int) -> Unit,
    onOpen: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolGlyph(FeedTabIcons.clock)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("clock", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    text = "a rolling window over your follows",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            Text(
                text = "${hours}h ▾",
                style = BonyType.tag.copy(color = BonyColors.Accent),
            )
        }
        if (expanded) {
            // Hour chips rather than a slider: the home feed already offers these
            // exact windows, so a feed opened here matches one opened there.
            Row(modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, bottom = 12.dp)) {
                listOf(1, 3, 6, 12, 24).forEach { option ->
                    Text(
                        text = if (option == hours) "[$option]" else "$option",
                        style = BonyType.tag.copy(
                            color = if (option == hours) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        modifier = Modifier
                            .clickable { onHours(option) }
                            .padding(end = 12.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "view →",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.clickable(onClick = onOpen).padding(top = 4.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun InputRow(
    source: FeedSource,
    title: String,
    subtitle: String,
    fieldLabel: String,
    value: String,
    expanded: Boolean,
    onExpand: () -> Unit,
    onChange: (String) -> Unit,
    onGo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolGlyph(FeedTabIcons.forSource(source))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = BonyType.body.copy(color = BonyColors.Text))
                Text(subtitle, style = BonyType.meta.copy(color = BonyColors.TextMute))
            }
            Text(
                text = if (expanded) "▴" else "▾",
                style = BonyType.body.copy(color = BonyColors.TextMute),
            )
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text(fieldLabel, style = BonyType.caption) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "view →",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.clickable(onClick = onGo).padding(vertical = 8.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(BonyColors.Rule))
}

@Composable
private fun PeopleRow(
    expanded: Boolean,
    onExpand: () -> Unit,
    name: String,
    onName: (String) -> Unit,
    keys: String,
    onKeys: (String) -> Unit,
    onGo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExpand)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolGlyph(FeedTabIcons.people)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("group of people", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "a hand-picked timeline — nobody gets added to your follows",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
            Text(
                text = if (expanded) "▴" else "▾",
                style = BonyType.body.copy(color = BonyColors.TextMute),
            )
        }
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 66.dp, end = 14.dp, bottom = 12.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text("name this feed", style = BonyType.caption) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = keys,
                    onValueChange = onKeys,
                    label = { Text("npub or hex, one per line", style = BonyType.caption) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "view →",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.clickable(onClick = onGo).padding(vertical = 6.dp),
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(start = 66.dp).height(1.dp).background(BonyColors.Rule))
}

private fun savedSubtitle(source: FeedSource): String = when (source) {
    is FeedSource.Follows -> "everyone you follow"
    is FeedSource.Clock -> "your follows, ${source.hours}h window"
    is FeedSource.Suggested -> "the curated accounts"
    is FeedSource.Global -> "your global relays"
    is FeedSource.People -> "${source.pubkeys.size} hand-picked people"
    is FeedSource.Relay -> source.urls.joinToString(", ") { it.substringAfter("://", it).substringBefore('/') }
    is FeedSource.Hashtag -> source.tags.joinToString(" ") { "#$it" }
    is FeedSource.Polls -> "polls from your follows"
    is FeedSource.Unsupported -> "not supported by this version"
}
