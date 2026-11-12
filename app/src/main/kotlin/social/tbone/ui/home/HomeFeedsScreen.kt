package social.tbone.ui.home

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.home.FeedSlot
import social.tbone.home.FeedSource
import androidx.compose.ui.graphics.vector.ImageVector
import social.tbone.ui.feed.FeedTabIcons
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder

/**
 * Home feeds editor.
 *
 * Two things it deliberately does not do: drag-and-drop (arrow buttons are
 * unambiguous, work with TalkBack, and do not fight the app's gesture
 * navigation) and a feed naming field on every source (a hashtag names itself,
 * a relay names itself — only a people list needs a name).
 */
@Composable
fun HomeFeedsScreen(
    onBack: () -> Unit,
    viewModel: HomeFeedsViewModel = hiltViewModel(),
) {
    val layout by viewModel.layout.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    // Non-null while a slot's tab is being re-pointed at a different feed.
    var showPicker by remember { mutableStateOf<FeedSlot?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
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
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("home feeds", style = BonyType.body.copy(color = BonyColors.Text))
                Text(
                    "swap, reorder or hide the tabs on the home screen",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        notice?.let { nudge -> nudgeHint(nudge) }?.let { text ->
            Text(
                text = text,
                style = BonyType.meta.copy(color = BonyColors.Warn),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .clickable { viewModel.dismissNotice() },
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "the follows feed is always the first tab — it is where the app opens. " +
                        "tap a feed to change it, the arrows reorder it, the switch hides it without deleting it.",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(14.dp),
                )
            }

            items(layout.slots, key = { it.id }) { slot ->
                FeedSlotRow(
                    slot = slot,
                    index = layout.indexOf(slot.id),
                    total = layout.slots.size,
                    canHide = layout.visibleSlots.size > 1 && slot.source !is FeedSource.Follows,
                    canRemove = slot.source !is FeedSource.Follows,
                    onEdit = { showPicker = slot },
                    onUp = { viewModel.moveUp(slot) },
                    onDown = { viewModel.moveDown(slot) },
                    onVisible = { viewModel.setVisible(slot, it) },
                    onRemove = { viewModel.remove(slot) },
                )
            }

            item {
                Text(
                    text = "+ add a feed",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .padding(14.dp)
                        .appBorder(1.dp, BonyColors.AccentDim)
                        .clickable { adding = "choose" }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            item {
                Text(
                    text = "reset to the four default feeds",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .padding(start = 14.dp, end = 14.dp, bottom = 24.dp)
                        .clickable { viewModel.reset() }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }

    showPicker?.let { slot ->
        SourcePickerSheet(
            slot = slot,
            onDismiss = { showPicker = null },
            onPick = { source ->
                viewModel.replaceSource(slot, source)
                showPicker = null
            },
            onPickClock = { hours ->
                viewModel.setClockHours(slot, hours)
                showPicker = null
            },
        )
    }

    adding?.let { kind ->
        AddFeedSheet(
            kind = kind,
            onPickKind = { picked -> adding = picked },
            onDismiss = { adding = null },
            onHashtag = { tag -> viewModel.addHashtag(tag); adding = null },
            onRelay = { url -> viewModel.addRelay(url); adding = null },
            onPeople = { name, keys -> viewModel.addPeople(name, keys); adding = null },
        )
    }
}

@Composable
private fun FeedSlotRow(
    slot: FeedSlot,
    index: Int,
    total: Int,
    canHide: Boolean,
    canRemove: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .appBorder(1.dp, if (slot.visible) BonyColors.Rule else BonyColors.RuleStrong)
            .background(BonyColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = FeedTabIcons.forSource(slot.source) as ImageVector,
            contentDescription = null,
            tint = if (slot.visible) BonyColors.Accent else BonyColors.TextMute,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = slot.source.label,
                style = BonyType.body.copy(
                    color = if (slot.visible) BonyColors.Text else BonyColors.TextMute,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = describe(slot.source) + if (slot.visible) "" else " · hidden",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Reorder: slot 0 is pinned, so up is disabled there.
        NavArrow("↑", enabled = index > 1, onClick = onUp)
        NavArrow("↓", enabled = index < total - 1 && index > 0, onClick = onDown)
        // Hide/show, then remove.
        Text(
            text = if (slot.visible) "◉" else "○",
            style = BonyType.body.copy(color = if (slot.visible) BonyColors.Accent else BonyColors.TextMute),
            modifier = Modifier
                .clickable(enabled = canHide) { onVisible(!slot.visible) }
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        Text(
            text = "✕",
            style = BonyType.meta.copy(color = if (canRemove) BonyColors.TextMute else BonyColors.RuleStrong),
            modifier = Modifier
                .clickable(enabled = canRemove, onClick = onRemove)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NavArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        style = BonyType.meta.copy(color = if (enabled) BonyColors.TextMute else BonyColors.RuleStrong),
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
    )
}

/** One line explaining what a feed actually reads. */
internal fun describe(source: FeedSource): String = when (source) {
    is FeedSource.Follows -> "people you follow"
    is FeedSource.Clock -> "your follows, ${source.hours}h window"
    is FeedSource.Suggested -> "the curated suggested accounts"
    is FeedSource.Global -> "every relay in your global set"
    is FeedSource.People -> "${source.pubkeys.size} people"
    is FeedSource.Relay -> source.urls.joinToString(", ") { it.substringAfter("://", it).substringBefore('/') }
    is FeedSource.Hashtag -> source.tags.joinToString(" ") { "#$it" }
    is FeedSource.Polls -> "polls from your follows"
    is FeedSource.Unsupported -> "not supported by this version"
}

@Composable
private fun SourcePickerSheet(
    slot: FeedSlot,
    onDismiss: () -> Unit,
    onPick: (FeedSource) -> Unit,
    onPickClock: (Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text("what should this tab show?", style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(10.dp))
            val options = listOf(
                FeedSource.Clock(hours = 6) to "clock · 6 hour window",
                FeedSource.Clock(hours = 1) to "clock · last hour",
                FeedSource.Clock(hours = 24) to "clock · last 24 hours",
                FeedSource.Suggested() to "suggested accounts",
                FeedSource.Global() to "global relays",
                FeedSource.Polls() to "polls",
            )
            options.forEach { (source, label) ->
                val selected = slot.source::class == source::class
                Text(
                    text = (if (selected) "◉ " else "○ ") + label,
                    style = BonyType.meta.copy(
                        color = if (selected) BonyColors.Accent else BonyColors.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (source is FeedSource.Clock) onPickClock(source.hours) else onPick(source)
                        }
                        .padding(vertical = 9.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "cancel",
                style = BonyType.tag.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Why an action was refused, in words.
 *
 * The copy stays beside the type it explains so a new refusal cannot be added
 * without somebody writing down what it means.
 */
internal fun nudgeHint(nudge: HomeFeedsViewModel.Nudge): String {
    val name = nudge.feedName
    return when (nudge.target) {
        HomeFeedsViewModel.Target.FOLLOWS -> when (nudge.action) {
            HomeFeedsViewModel.Nudge.Action.HIDE ->
                "the $name feed always stays visible — it is where the app opens"
            HomeFeedsViewModel.Nudge.Action.MOVE ->
                "the $name feed is always the first tab"
            else -> "the $name feed cannot be removed — the home screen always needs it"
        }
        HomeFeedsViewModel.Target.LAST_VISIBLE ->
            "keep at least one feed visible — a tab bar with nothing on it is a dead end"
        HomeFeedsViewModel.Target.MAX_SLOTS ->
            "eight feeds is the limit; hide or remove one first"
        HomeFeedsViewModel.Target.ALREADY_THERE ->
            "there is already a $name feed — edit that one instead"
        HomeFeedsViewModel.Target.EMPTY_INPUT ->
            "type a $name to follow"
        HomeFeedsViewModel.Target.BAD_RELAY ->
            "that does not look like a relay address — try wss://relay.example"
        HomeFeedsViewModel.Target.NO_KEYS ->
            "no $name in there — paste npub or 64-character hex keys"
    }
}

@Composable
private fun AddFeedSheet(
    kind: String,
    onPickKind: (String) -> Unit,
    onDismiss: () -> Unit,
    onHashtag: (String) -> Unit,
    onRelay: (String) -> Unit,
    onPeople: (String, String) -> Unit,
) {
    var mode by remember(kind) { mutableStateOf(if (kind == "choose") "" else kind) }
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.85f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            if (mode.isEmpty()) {
                // First step: what kind of feed is being added. Hashtag, relay
                // and people cover everything the tool ships in build 1; polls
                // already exist as a tab choice, so they are not offered here.
                Text("add a feed", style = BonyType.body.copy(color = BonyColors.Text))
                Spacer(Modifier.height(10.dp))
                listOf(
                    "hashtag" to "everyone posting a tag",
                    "relay" to "one relay's timeline",
                    "people" to "a hand-picked group",
                ).forEach { (option, description) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPickKind(option) }
                            .padding(vertical = 9.dp),
                    ) {
                        Text(option, style = BonyType.meta.copy(color = BonyColors.Text))
                        Text(
                            description,
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "cancel",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("hashtag", "relay", "people").forEach { option ->
                    Text(
                        text = if (mode == option) "[$option]" else option,
                        style = BonyType.tag.copy(
                            color = if (mode == option) BonyColors.Accent else BonyColors.TextMute,
                        ),
                        modifier = Modifier.clickable { mode = option },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (mode == "people") {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("name this feed", style = BonyType.caption) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = {
                    Text(
                        text = when (mode) {
                            "hashtag" -> "hashtag, e.g. bitcoin"
                            "relay" -> "relay, e.g. wss://nostr.wine"
                            else -> "npub or hex, one per line"
                        },
                        style = BonyType.caption,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "add",
                style = BonyType.button.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .appBorder(1.dp, BonyColors.AccentDim)
                    .clickable {
                        when (mode) {
                            "hashtag" -> onHashtag(text)
                            "relay" -> onRelay(text)
                            else -> onPeople(name, text)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "cancel",
                style = BonyType.tag.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
    }
}
