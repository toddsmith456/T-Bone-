package social.tbone.ui.toolbox.geohash

import social.tbone.ui.theme.appBorder
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.geohash.GeohashChannelEntry
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/**
 * Geohash messenger — a Telegram-style contact list of location channels.
 * A + button adds a geohash code; each channel appears as a contact/group row
 * and stays until you long-press it and choose delete. Long-press also offers
 * pin / move up / move down, exactly like Notes. Your nickname is set once at
 * the top and used consistently in every channel. No relay connection happens
 * here — channels only connect when you open one.
 */
@Composable
fun GeohashChannelScreen(
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: GeohashChannelViewModel = hiltViewModel(),
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val cacheMillis by viewModel.cacheMillis.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<GeohashChannelEntry?>(null) }

    GeohashChannelContent(
        channels = channels,
        nickname = nickname,
        cacheMillis = cacheMillis,
        onCacheChange = viewModel::setCacheMillis,
        onBack = onBack,
        onNicknameChange = viewModel::setNickname,
        onOpenChannel = onOpenChannel,
        onAdd = { showAdd = true },
        onChannelLongPress = { target = it },
    )

    if (showAdd) {
        AddChannelDialog(
            onDismiss = { showAdd = false },
            onAdd = { code ->
                viewModel.addChannel(code) { ok ->
                    if (ok) showAdd = false
                }
            },
        )
    }

    target?.let { channel ->
        GeohashChannelContextMenu(
            channel = channel,
            onDismiss = { target = null },
            onPinToggle = { viewModel.pinToggle(channel.code) },
            onPinToHomeToggle = { viewModel.pinToHomeToggle(channel.code) },
            onMoveUp = { viewModel.moveUp(channel.code) },
            onMoveDown = { viewModel.moveDown(channel.code) },
            onDelete = { viewModel.deleteChannel(channel.code) },
        )
    }
}

@Composable
private fun GeohashChannelContent(
    channels: List<GeohashChannelEntry>,
    nickname: String,
    cacheMillis: Long,
    onCacheChange: (Long) -> Unit,
    onBack: () -> Unit,
    onNicknameChange: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onAdd: () -> Unit,
    onChannelLongPress: (GeohashChannelEntry) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg)
                .statusBarsPadding(),
        ) {
            GeohashChannelTitleBar(onBack)
            CacheSelector(cacheMillis, onCacheChange)
            NicknameEditor(nickname, onNicknameChange)
            Text(
                text = "long-press a channel to pin, move or delete",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            GeohashChannelList(
                channels = channels,
                onOpenChannel = onOpenChannel,
                onChannelLongPress = onChannelLongPress,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 12.dp)
                .appBorder(1.dp, BonyColors.AccentDim)
                .background(BonyColors.Surface)
                .clickable(onClick = onAdd)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(text = "+", style = BonyType.button.copy(color = BonyColors.Accent))
        }
    }
}

@Composable
private fun GeohashChannelTitleBar(onBack: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", style = BonyType.body.copy(color = BonyColors.TextMute), modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.width(8.dp))
            Text("location channels", style = BonyType.body.copy(color = BonyColors.Text), modifier = Modifier.weight(1f))
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun CacheSelector(selected: Long, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("never", "30 min", "1 hr", "5 hr", "1 day", "1 week")
        .zip(social.tbone.settings.AppSettings.GEOHASH_CACHE_OPTIONS)
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text("message cache: ${options.firstOrNull { it.second == selected }?.first ?: "never"} ${if (expanded) "▴" else "▾"}",
            style = BonyType.meta.copy(color = BonyColors.Text),
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp))
        if (expanded) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                options.forEach { (label, value) ->
                    Text(label, style = BonyType.meta.copy(color = if (selected == value) BonyColors.Accent else BonyColors.TextMute),
                        modifier = Modifier.clickable { onSelect(value); expanded = false }.padding(vertical = 8.dp))
                }
            }
            Text("Stores received messages on this device. Selecting never clears the cache.", style = BonyType.caption.copy(color = BonyColors.TextMute))
        }
    }
}

@Composable
private fun NicknameEditor(nickname: String, onNicknameChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "name",
            style = BonyType.meta.copy(color = BonyColors.TextMute),
            modifier = Modifier.width(56.dp),
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            placeholder = { Text("your nickname", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            singleLine = true,
            textStyle = BonyType.body.copy(color = BonyColors.Text),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun GeohashChannelList(
    channels: List<GeohashChannelEntry>,
    onOpenChannel: (String) -> Unit,
    onChannelLongPress: (GeohashChannelEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "no channels yet — tap + to add a geohash",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
        }
    } else {
        val sorted = channels.sortedWith(
            compareByDescending<GeohashChannelEntry> { it.pinned }.thenBy { it.sortOrder },
        )
        LazyColumn(modifier = modifier.navigationBarsPadding()) {
            items(sorted, key = { it.code }) { channel ->
                ChannelRow(
                    channel = channel,
                    onClick = { onOpenChannel(channel.code) },
                    onLongClick = { onChannelLongPress(channel) },
                )
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun GeohashChannelContextMenu(
    channel: GeohashChannelEntry,
    onDismiss: () -> Unit,
    onPinToggle: () -> Unit,
    onPinToHomeToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = "channel ${channel.code}" + if (channel.pinned) " · pinned" else "",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            PopupRow(if (channel.pinned) "unpin" else "pin", onClick = { onPinToggle(); onDismiss() })
            PopupRow(
                if (channel.pinnedToHome) "remove from home" else "pin to home",
                onClick = { onPinToHomeToggle(); onDismiss() },
            )
            PopupRow("move up", onClick = { onMoveUp(); onDismiss() })
            PopupRow("move down", onClick = { onMoveDown(); onDismiss() })
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            PopupRow("delete", destructive = true, onClick = { onDelete(); onDismiss() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelRow(
    channel: GeohashChannelEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Contact avatar: circle with the first geohash char.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(BonyColors.AccentBg, CircleShape)
                .border(1.dp, BonyColors.AccentDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = channel.code.take(1).uppercase(),
                style = BonyType.body.copy(color = BonyColors.Accent),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.code,
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = "pinned",
                        tint = BonyColors.Accent,
                        modifier = Modifier.height(13.dp),
                    )
                }
                if (channel.pinnedToHome) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "home", style = BonyType.caption.copy(color = BonyColors.Accent))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "location channel",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            )
        }
        Text(text = "›", style = BonyType.body.copy(color = BonyColors.TextMute))
    }
}

@Composable
private fun AddChannelDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
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
                .padding(horizontal = 40.dp)
                .appBorder(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(text = "add channel", style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                placeholder = { Text("geohash (e.g. 9q8yyk)", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "cancel",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable(onClick = onDismiss).padding(10.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "add",
                    style = BonyType.tag.copy(
                        color = if (code.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier
                        .appBorder(1.dp, if (code.isNotBlank()) BonyColors.AccentDim else BonyColors.Rule)
                        .clickable(enabled = code.isNotBlank()) { onAdd(code) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun PopupRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.body.copy(
            color = if (destructive) BonyColors.Danger else BonyColors.Text,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}
