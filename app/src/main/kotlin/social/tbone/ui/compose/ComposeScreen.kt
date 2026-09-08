package social.tbone.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.search.ProfileMatch
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

/** The token being typed after "@" — letters, digits, _ . - (e.g. "@jack_1"). */
private val MENTION_TOKEN_REGEX = Regex("""(?:^|\s)@([\p{L}\p{N}_.\-]*)$""")

@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val mentionSearching by viewModel.mentionSearching.collectAsStateWithLifecycle()

    // ── Media pickers (Blossom uploads) ──────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.attachMedia(it, "image", "image/jpeg") }
    }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let { viewModel.attachMedia(it, "video", "video/mp4") }
    }
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var tagList by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // Mention label ("@Name") → hex pubkey, resolved to nostr:npub1… URIs on send.
    val mentionMap = remember { mutableStateMapOf<String, String>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusRequester = remember { FocusRequester() }

    // The @-mention token currently being typed (null when the caret isn't in one).
    val mentionQuery = mentionQueryAt(fieldValue.text, fieldValue.selection.end)
    LaunchedEffect(mentionQuery) { viewModel.setMentionQuery(mentionQuery) }

    // Replaces the in-progress "@query" token with "@Name " and remembers the
    // mapping so SEND can convert it into a nostr:npub1… mention + p-tag.
    fun insertMention(match: ProfileMatch) {
        val query = mentionQueryAt(fieldValue.text, fieldValue.selection.end) ?: return
        val name = match.profile?.bestName?.trim()?.takeIf { it.isNotBlank() }
            ?: abbreviateNpub(match.pubkey)
        val tokenStart = (fieldValue.selection.end - (query.length + 1)).coerceAtLeast(0)
        val insertion = "@$name"
        val newText = fieldValue.text.substring(0, tokenStart) + insertion + " " + fieldValue.text.substring(fieldValue.selection.end)
        fieldValue = fieldValue.copy(
            text = newText,
            selection = TextRange((tokenStart + insertion.length + 1).coerceAtMost(newText.length)),
        )
        mentionMap[name] = match.pubkey
        viewModel.setMentionQuery(null)
    }

    // Inserts a single character at the caret (toolbar @ / # buttons).
    fun insertCharAtCaret(ch: String) {
        val pos = fieldValue.selection.end.coerceIn(0, fieldValue.text.length)
        val newText = fieldValue.text.substring(0, pos) + ch + fieldValue.text.substring(pos)
        fieldValue = fieldValue.copy(
            text = newText,
            selection = TextRange((pos + ch.length).coerceAtMost(newText.length)),
        )
        focusRequester.requestFocus()
    }

    var textInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.initialText) {
        if (!textInitialized && uiState.initialText.isNotEmpty()) {
            fieldValue = fieldValue.copy(text = uiState.initialText)
            textInitialized = true
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    uiState.error?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // Insert the uploaded media URL at the caret and clear it for the next one.
    LaunchedEffect(uiState.lastUploadUrl) {
        uiState.lastUploadUrl?.let { url ->
            insertCharAtCaret(url + " ")
            viewModel.consumeUploadedUrl()
        }
    }
    uiState.uploadError?.let { err ->
        LaunchedEffect(err) {
            snackbarHostState.showSnackbar("upload failed: $err")
        }
    }

    val isReply = uiState.replyToEvent != null
    val isQuote = uiState.quoteToEvent != null
    val title = when {
        isReply -> "compose.reply"
        isQuote -> "compose.quote"
        else -> "compose.new"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            // ── Top bar ───────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Bg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "×",
                    style = BonyType.body.copy(color = BonyColors.TextMute),
                    modifier = if (!uiState.isPublishing)
                        Modifier.clickable { onBack() }
                    else Modifier,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )

                if (uiState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BonyColors.Accent,
                        strokeWidth = 1.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .border(1.dp, if (fieldValue.text.isNotBlank()) BonyColors.Accent else BonyColors.Rule)
                            .background(if (fieldValue.text.isNotBlank()) BonyColors.Accent else BonyColors.Bg)
                            .then(if (fieldValue.text.isNotBlank()) Modifier.clickable {
                                // Mentions (@Name) become nostr:npub1… URIs so the
                                // published note renders a nice, clickable @Name —
                                // and each mentioned pubkey becomes a NIP-08 p-tag.
                                val finalText = social.tbone.util.LinkCleaner.cleanUrlsInText(
                                    resolveMentions(fieldValue.text, mentionMap),
                                )
                                val npubs = tagList.filter { it.startsWith("npub1") } +
                                    mentionMap.values.mapNotNull { hex ->
                                        runCatching { Nip19.hexToNpub(hex) }.getOrNull()
                                    }
                                val hashtags = tagList.filter { it.startsWith("#") }
                                viewModel.publish(finalText, { onBack() }, npubs, hashtags)
                            } else Modifier)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "SEND ↗",
                            style = BonyType.button.copy(
                                color = if (fieldValue.text.isNotBlank()) BonyColors.Bg else BonyColors.TextMute,
                            ),
                        )
                    }
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

            // ── Reply/quote context card ──────────────────────────────────────
            uiState.replyToEvent?.let { parent ->
                ComposeContextCard(event = parent, label = "↳ REPLYING TO")
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            }
            uiState.quoteToEvent?.let { original ->
                ComposeContextCard(event = original, label = "↳ QUOTING")
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            }

            // ── Editor ────────────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).padding(14.dp)) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = when {
                            isReply -> "> reply to thread…"
                            isQuote -> "> add a comment…"
                            else -> "> what's on your mind…"
                        },
                        style = BonyType.body.copy(color = BonyColors.TextMute),
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { nv ->
                        // Auto-strip tracking parameters from pasted links: a
                        // big jump in length means a paste — clean any URLs in it.
                        val jumped = nv.text.length - fieldValue.text.length > 2
                        fieldValue = if (jumped) {
                            nv.copy(text = social.tbone.util.LinkCleaner.cleanUrlsInText(nv.text))
                        } else nv
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester),
                    textStyle = BonyType.body.copy(color = BonyColors.Text),
                    cursorBrush = SolidColor(BonyColors.Accent),
                    enabled = !uiState.isPublishing,
                )

                // @ mention picker — floats above the typing area, never inline.
                if (mentionQuery != null && (mentionSuggestions.isNotEmpty() || mentionSearching)) {
                    MentionPicker(
                        suggestions = mentionSuggestions,
                        searching = mentionSearching,
                        onSelect = { match -> insertMention(match) },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth(),
                    )
                }
            }

            // ── Poll builder ─────────────────────────────────────────────────
            if (uiState.pollEnabled) {
                PollBuilder(
                    options = uiState.pollOptions,
                    multiple = uiState.pollMultiple,
                    onOptionChange = viewModel::setPollOption,
                    onAddOption = viewModel::addPollOption,
                    onRemoveOption = viewModel::removePollOption,
                    onToggleType = viewModel::togglePollType,
                )
            }

            // ── Tag bar: add @mentions / #hashtags to the note ────────────────
            if (tagList.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BonyColors.Surface)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tagList.take(6).forEach { tag ->
                        Text(
                            text = "$tag  ✕",
                            style = BonyType.caption.copy(color = BonyColors.Accent),
                            modifier = Modifier
                                .border(1.dp, BonyColors.AccentDim)
                                .clickable { tagList = tagList - tag }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            // ── Blossom server chooser ────────────────────────────────────────
            if (uiState.blossomServers.isNotEmpty()) {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.background(BonyColors.Surface),
                ) {
                    item {
                        BlossomChip(
                            label = "random",
                            selected = uiState.blossomServer == null,
                            onClick = { viewModel.setBlossomServer(null) },
                        )
                    }
                    items(uiState.blossomServers, key = { it }) { server ->
                        BlossomChip(
                            label = server.removePrefix("https://").removePrefix("http://")
                                .trimEnd('/'),
                            selected = uiState.blossomServer == server,
                            onClick = { viewModel.setBlossomServer(server) },
                        )
                    }
                }
            }

            // ── Toolbar ───────────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !uiState.isUploading) {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(imageVector = Icons.Default.Image, contentDescription = "image", modifier = Modifier.size(16.dp), tint = if (uiState.isUploading) BonyColors.TextMute else BonyColors.TextMute)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (uiState.isUploading) "…uploading" else "image",
                        style = BonyType.meta.copy(
                            color = if (uiState.isUploading) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = !uiState.isUploading) {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }) {
                    Icon(imageVector = Icons.Default.Videocam, contentDescription = "video", modifier = Modifier.size(16.dp), tint = BonyColors.TextMute)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "video",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
                Spacer(Modifier.width(16.dp))
                // "@" drops a @ at the caret and pops the mention picker.
                Text("@", style = BonyType.meta.copy(
                        color = if (mentionQuery != null) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier.clickable { insertCharAtCaret("@") })
                Spacer(Modifier.width(16.dp))
                Text("#", style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { insertCharAtCaret("#") })
                Spacer(Modifier.width(16.dp))
                // NIP-88 poll toggle — highlighted when active.
                Text(
                    text = "📊",
                    style = BonyType.meta.copy(
                        color = if (uiState.pollEnabled) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier.clickable { viewModel.togglePoll() },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${fieldValue.text.length} / ∞",
                    style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// ── Blossom server chip ──────────────────────────────────────────────────────

@Composable
private fun BlossomChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.caption.copy(
            color = if (selected) BonyColors.Bg else BonyColors.TextMute,
        ),
        modifier = Modifier
            .background(if (selected) BonyColors.Accent else BonyColors.Bg)
            .border(1.dp, if (selected) BonyColors.Accent else BonyColors.Rule)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

// ── @ mention helpers ────────────────────────────────────────────────────────

/**
 * If the caret is at the end of an "@…" token (preceded by start-of-text or
 * whitespace), returns the query text typed so far ("" right after "@"),
 * otherwise null — meaning no mention is in progress.
 */
private fun mentionQueryAt(text: String, caret: Int): String? {
    if (caret <= 0) return null
    val before = text.take(caret)
    val m = MENTION_TOKEN_REGEX.find(before) ?: return null
    if (m.range.last + 1 != caret) return null
    return m.groupValues[1]
}

/**
 * Turns the remembered "@Name" mention labels back into nostr:npub1… URIs so
 * the published note renders a clickable @Name and other clients can resolve
 * the mention. Longest labels first so a label that prefixes another wins.
 */
private fun resolveMentions(content: String, mentions: Map<String, String>): String {
    if (mentions.isEmpty()) return content
    var out = content
    mentions.entries.sortedByDescending { it.key.length }.forEach { (name, hex) ->
        if (name.isNotBlank() && out.contains("@$name")) {
            val npub = runCatching { Nip19.hexToNpub(hex) }.getOrNull() ?: return@forEach
            out = out.replace("@$name", "nostr:$npub")
        }
    }
    return out
}

private fun abbreviateNpub(hex: String): String =
    runCatching { Nip19.hexToNpub(hex) }.getOrNull()
        ?.let { "${it.take(9)}…${it.takeLast(4)}" }
        ?: hex.take(9) + "…" + hex.takeLast(4)

@Composable
private fun MentionPicker(
    suggestions: List<ProfileMatch>,
    searching: Boolean,
    onSelect: (ProfileMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .background(BonyColors.Surface)
            .border(1.dp, BonyColors.AccentDim, RoundedCornerShape(10.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.SurfaceAlt)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = "mention…",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(1f),
            )
            if (searching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = BonyColors.Accent,
                    strokeWidth = 1.dp,
                )
            }
        }
        if (suggestions.isEmpty()) {
            Text(
                text = "searching…",
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                items(suggestions, key = { it.pubkey }) { match ->
                    MentionRow(match = match, onClick = { onSelect(match) })
                }
            }
        }
    }
}

@Composable
private fun MentionRow(match: ProfileMatch, onClick: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(pubkeyHex = match.pubkey, profile = match.profile, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = match.profile?.bestName ?: abbreviateNpub(match.pubkey),
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = match.profile?.nip05?.let { "✓ $it" }
                    ?: runCatching { Nip19.hexToNpub(match.pubkey) }.getOrNull()
                        ?.let { "${it.take(9)}…${it.takeLast(4)}" }
                    ?: abbreviateNpub(match.pubkey)
                Text(
                    text = sub,
                    style = BonyType.metaDim.copy(
                        color = if (match.profile?.nip05 != null) BonyColors.Accent
                                else BonyColors.TextMute,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = BonyColors.Rule)
    }
}

@Composable
private fun ComposeContextCard(event: Event, label: String) {
    val noteId = remember(event.id) { Nip19.hexToNote(event.id) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = BonyType.caption.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "note1${noteId.drop(5).take(10)}…",
            style = BonyType.metaDim.copy(color = BonyColors.Accent),
        )
        if (event.content.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.content.take(200),
                style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Poll builder (NIP-88) ────────────────────────────────────────────────────

@Composable
private fun PollBuilder(
    options: List<String>,
    multiple: Boolean,
    onOptionChange: (Int, String) -> Unit,
    onAddOption: () -> Unit,
    onRemoveOption: (Int) -> Unit,
    onToggleType: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📊 poll",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier.weight(1f),
            )
            // Single / multiple choice toggle
            Box(
                modifier = Modifier
                    .border(1.dp, if (multiple) BonyColors.Accent else BonyColors.Rule)
                    .clickable(onClick = onToggleType)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (multiple) "☑ multiple" else "○ single",
                    style = BonyType.tag.copy(
                        color = if (multiple) BonyColors.Accent else BonyColors.TextMute,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "question goes in the note text above",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
        )
        Spacer(Modifier.height(8.dp))

        options.forEachIndexed { index, option ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (multiple) "☐" else "○",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = option,
                    onValueChange = { onOptionChange(index, it) },
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, BonyColors.Rule)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    textStyle = BonyType.bodyDim.copy(color = BonyColors.Text),
                    cursorBrush = SolidColor(BonyColors.Accent),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (option.isEmpty()) {
                            Text(
                                text = "option ${index + 1}",
                                style = BonyType.bodyDim.copy(color = BonyColors.TextMute),
                            )
                        }
                        inner()
                    },
                )
                if (options.size > 2) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "×",
                        style = BonyType.meta.copy(color = BonyColors.Danger),
                        modifier = Modifier
                            .padding(6.dp)
                            .clickable { onRemoveOption(index) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        if (options.size < 10) {
            Text(
                text = "+ add option",
                style = BonyType.tag.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .clickable(onClick = onAddOption),
            )
        }
    }
}
