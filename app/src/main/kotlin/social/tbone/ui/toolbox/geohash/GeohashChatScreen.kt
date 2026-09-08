package social.tbone.ui.toolbox.geohash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Nip19
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A single geohash channel's chat. The relay connection is made when this
 * screen opens (and torn down on leave), so idle channel browsing never bogs
 * the app down. Shows the 40 newest messages; yours appear immediately.
 */
@Composable
fun GeohashChatScreen(
    code: String,
    onBack: () -> Unit,
    viewModel: GeohashChatViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Connect only while this screen is open.
    LaunchedEffect(code) {
        viewModel.join(code)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", style = BonyType.body.copy(color = BonyColors.TextMute), modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("channel ${code}", style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (nickname.isNotBlank()) "posting as $nickname" else "no nickname set",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            // Live indicator: goes solid once a relay answers.
            Text(
                text = if (live) "● live" else "○ connecting",
                style = BonyType.caption.copy(
                    color = if (live) BonyColors.Accent else BonyColors.TextMute,
                ),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        error?.let {
            Text(
                text = it,
                style = BonyType.meta.copy(color = BonyColors.Danger),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        // Honest note: kind-20000 geohash events are EPHEMERAL — relays don't
        // store them, so a channel only shows what's posted while you're here.
        Text(
            text = "geohash messages are live-only (relays don't keep them) — you'll see messages posted while you're in this channel",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )

        // ── Messages ──────────────────────────────────────────────────────────
        if (messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "no messages in this channel yet — say hi",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(m)
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
            }
            // Scroll to the newest (bottom) when new messages arrive — the
            // list is chronological, oldest at the top.
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) {
                    listState.scrollToItem(messages.lastIndex)
                }
            }
        }

        // ── Composer ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("message…", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, if (text.isNotBlank()) BonyColors.Accent else BonyColors.Rule)
                    .then(if (text.isNotBlank()) Modifier.clickable {
                        viewModel.send(text)
                        text = ""
                    } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "SEND",
                    style = BonyType.tag.copy(
                        color = if (text.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(m: GeohashMessage) {
    // Own messages align right and use the accent; others align left.
    val alignment = if (m.own) Alignment.CenterEnd else Alignment.CenterStart
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(if (m.own) BonyColors.AccentBg else BonyColors.SurfaceAlt)
                .border(1.dp, if (m.own) BonyColors.AccentDim else BonyColors.Rule)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = m.nickname?.takeIf { it.isNotBlank() } ?: shortNpub(m.pubkey),
                    style = BonyType.metaDim.copy(
                        color = if (m.own) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = m.createdAt.formatChatTime(),
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(text = m.content, style = BonyType.body.copy(color = BonyColors.Text))
        }
    }
}

private fun shortNpub(hex: String): String =
    runCatching { Nip19.hexToNpub(hex) }.getOrNull()?.let { "${it.take(9)}…" } ?: "${hex.take(8)}…"

private fun Long.formatChatTime(): String =
    Instant.ofEpochSecond(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d · HH:mm"))
