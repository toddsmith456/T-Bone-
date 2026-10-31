package social.tbone.ui.compose

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import social.tbone.nostr.ProfileContent
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.feed.NoteBodyText
import social.tbone.ui.feed.NoteMediaContent
import social.tbone.ui.feed.QuotedNoteCard
import social.tbone.ui.feed.parseNoteContent

import social.tbone.ui.theme.appBorder
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.Tunables
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.search.ProfileMatch
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appCornerShape

/** The token being typed after "@" — letters, digits, _ . - (e.g. "@jack_1"). */
private val MENTION_TOKEN_REGEX = Regex("""(?:^|\s)@([\p{L}\p{N}_.\-]*)$""")

@Composable
fun ComposeScreen(
    onExit: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val switchingAccount by viewModel.switchingAccount.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val currentOnExit by rememberUpdatedState(onExit)
    val exitGate = remember(viewModel) { ComposeExitGate() }
    fun exitComposeOnce() = exitGate.runOnce { currentOnExit() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) { ComposeEffect.Published -> exitComposeOnce() }
        }
    }
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val mentionSearching by viewModel.mentionSearching.collectAsStateWithLifecycle()

    // ── Media pickers (Blossom uploads) ──────────────────────────────────────
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        uris.forEach { viewModel.attachMedia(it, "image") }
    }
    // GetContent keeps less-common video containers available through document
    // providers instead of limiting the chooser to the system photo picker.
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { viewModel.attachMedia(it, "video") }
    }
    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { viewModel.attachMedia(it, "audio") }
    }
    var fieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var tagInput by rememberSaveable { mutableStateOf("") }
    var tagList by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // Mention label ("@Name") → hex pubkey, resolved to nostr:npub1… URIs on send.
    val mentionMap = remember { mutableStateMapOf<String, String>() }
    val mentionTransformation = remember(mentionMap.toMap()) {
        VisualTransformation { source ->
            val text = source.text
            val annotated = buildAnnotatedString {
                append(text)
                mentionMap.keys.forEach { name ->
                    val token = "@$name"
                    var from = 0
                    while (token.isNotEmpty()) {
                        val start = text.indexOf(token, from)
                        if (start < 0) break
                        val end = start + token.length
                        val beginsToken = start == 0 || !text[start - 1].isLetterOrDigit()
                        if (beginsToken) addStyle(SpanStyle(color = BonyColors.Accent), start, end)
                        from = end
                    }
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }
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

    // Uploaded media references live below the writing area. The caret stays
    // where the user was typing, so the first line remains available for prose.
    fun insertReferenceOnSecondLine(url: String) {
        val original = fieldValue.text
        val base = original.trimEnd()
        val referenceSection = base.substringAfterLast("\n\n", missingDelimiterValue = "")
        val existingReferenceLines = referenceSection.lineSequence().filter { it.isNotBlank() }.toList()
        val alreadyHasReferenceSection = "\n\n" in base && existingReferenceLines.isNotEmpty() &&
            existingReferenceLines.all { line ->
                val reference = line.trim()
                reference.startsWith("nostr:") || reference.startsWith("https://") || reference.startsWith("http://")
            }
        val separator = if (alreadyHasReferenceSection) "\n" else "\n\n"
        val nextText = "$base$separator$url "
        val selection = fieldValue.selection
        fieldValue = fieldValue.copy(
            text = nextText,
            selection = TextRange(
                selection.start.coerceAtMost(nextText.length),
                selection.end.coerceAtMost(nextText.length),
            ),
        )
    }

    // Queue-backed flows preserve every URL/error when a multi-picker returns
    // several items or the screen briefly stops collecting.
    LaunchedEffect(viewModel) {
        viewModel.uploadedMediaUrls.collect { url -> insertReferenceOnSecondLine(url) }
    }
    LaunchedEffect(viewModel) {
        viewModel.uploadErrors.collect { error -> snackbarHostState.showSnackbar("upload failed: $error") }
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

    val isReply = uiState.replyToEvent != null
    val isQuote = uiState.quoteToEvent != null || uiState.quoteFolderEvents.isNotEmpty()
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
                        Modifier.clickable { exitComposeOnce() }
                    else Modifier,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )

                ComposeAvatarSwitcher(activeAccount, accounts, profiles, viewModel::switchAccount, enabled = !switchingAccount && !uiState.isPublishing)
                Spacer(Modifier.width(8.dp))
                if (uiState.isPublishing || switchingAccount) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = BonyColors.Accent,
                        strokeWidth = 1.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .appBorder(1.dp, if (fieldValue.text.isNotBlank()) BonyColors.Accent else BonyColors.Rule)
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
                                viewModel.publish(finalText, npubs, hashtags)
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
            if (uiState.quoteFolderEvents.isNotEmpty()) {
                FolderQuoteContextCard(count = uiState.quoteFolderEvents.size)
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
                    visualTransformation = mentionTransformation,
                    cursorBrush = SolidColor(BonyColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
                                .appBorder(1.dp, BonyColors.AccentDim)
                                .clickable { tagList = tagList - tag }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text("◎ preview", style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { showPreview = true }.padding(4.dp))
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
                ComposeMediaAction(
                    icon = Icons.Default.Image,
                    label = "image",
                    uploading = uiState.uploadingKind == "image",
                    enabled = !uiState.isUploading,
                    onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                Spacer(Modifier.width(10.dp))
                ComposeMediaAction(
                    icon = Icons.Default.Videocam,
                    label = "video",
                    uploading = uiState.uploadingKind == "video",
                    enabled = !uiState.isUploading,
                    onClick = { videoPicker.launch("video/*") },
                )
                Spacer(Modifier.width(10.dp))
                ComposeMediaAction(
                    icon = Icons.Outlined.Audiotrack,
                    label = "audio",
                    uploading = uiState.uploadingKind == "audio",
                    enabled = !uiState.isUploading,
                    onClick = { audioPicker.launch("audio/*") },
                )
                Spacer(Modifier.width(10.dp))
                // "@" drops a @ at the caret and pops the mention picker.
                Text("@", style = BonyType.meta.copy(
                        color = if (mentionQuery != null) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier.clickable { insertCharAtCaret("@") })
                Spacer(Modifier.width(10.dp))
                Text("#", style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.clickable { insertCharAtCaret("#") })
                Spacer(Modifier.width(10.dp))
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
            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (showPreview) {
        val content = social.tbone.util.LinkCleaner.cleanUrlsInText(resolveMentions(fieldValue.text, mentionMap)).trim()
        NotePreviewDialog(
            content = viewModel.buildPreviewContent(content).ifBlank { "(empty note)" },
            pollEnabled = uiState.pollEnabled,
            pollOptions = uiState.pollOptions,
            pollMultiple = uiState.pollMultiple,
            quoteEvents = buildList {
                uiState.replyToEvent?.let { add(it) }
                uiState.quoteToEvent?.let { if (!contains(it)) add(it) }
                addAll(uiState.quoteFolderEvents.filterNot { contains(it) })
            },
            isReply = uiState.replyToEvent != null,
            activeAccount = activeAccount,
            activeProfile = activeAccount?.let { profiles[it.pubkey] },
            profiles = profiles,
            onDismiss = { showPreview = false },
        )
    }
}

// ── Blossom server chip ──────────────────────────────────────────────────────

@Composable
private fun SquareUploadSpinner() {
    val transition = rememberInfiniteTransition(label = "blossom-upload")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blossom-upload-rotation",
    )
    Box(
        modifier = Modifier
            .size(15.dp)
            .graphicsLayer { rotationZ = rotation }
            .border(1.5.dp, BonyColors.Accent, RoundedCornerShape(3.dp))
            .padding(3.dp)
            .border(1.dp, BonyColors.AccentDim, RoundedCornerShape(1.dp)),
    )
}

@Composable
private fun ComposeMediaAction(
    icon: ImageVector,
    label: String,
    uploading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (uploading) {
            SquareUploadSpinner()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(16.dp),
                tint = BonyColors.TextMute,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = if (uploading) "uploading…" else label,
            style = BonyType.meta.copy(
                color = if (uploading) BonyColors.Accent else BonyColors.TextMute,
            ),
        )
    }
}

@Composable
private fun BlossomChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.caption.copy(
            color = if (selected) BonyColors.Bg else BonyColors.TextMute,
        ),
        modifier = Modifier
            .background(if (selected) BonyColors.Accent else BonyColors.Bg)
            .appBorder(1.dp, if (selected) BonyColors.Accent else BonyColors.Rule)
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
    val mentionShape = appCornerShape(MaterialTheme.shapes.medium, RoundedCornerShape(10.dp))
    Column(
        modifier = modifier
            .shadow(8.dp, mentionShape)
            .clip(mentionShape)
            .background(BonyColors.Surface, mentionShape)
            .border(1.dp, BonyColors.AccentDim, mentionShape),
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
private fun FolderQuoteContextCard(count: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("↳ QUOTING BOOKMARK FOLDER", style = BonyType.caption.copy(color = BonyColors.TextMute))
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$count saved ${if (count == 1) "note" else "notes"} · nevent references are on the lines below your text",
            style = BonyType.meta.copy(color = BonyColors.Accent),
        )
    }
}

@Composable
private fun ComposeContextCard(event: Event, label: String) {
    val noteId = remember(event.id) { Nip19.hexToNote(event.id) }
    var expanded by remember(event.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BonyColors.SurfaceAlt)
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
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
                text = if (expanded) event.content else event.content.take(200),
                style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.content.length > 200 || event.content.lines().size > 4) {
                Text(if (expanded) "Show less" else "See more", style = BonyType.tag.copy(color = BonyColors.Accent),
                    modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 4.dp))
            }
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
                    .appBorder(1.dp, if (multiple) BonyColors.Accent else BonyColors.Rule)
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

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 190.dp),
        ) {
            itemsIndexed(options) { index, option ->
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
                        .appBorder(1.dp, BonyColors.Rule)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    textStyle = BonyType.bodyDim.copy(color = BonyColors.Text),
                    cursorBrush = SolidColor(BonyColors.Accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
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
        }

        if (options.size < Tunables.MAX_POLL_OPTIONS) {
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

// ── Compact avatar account switcher beside SEND ────────────────────────────
@Composable
private fun ComposeAvatarSwitcher(
    activeAccount: social.tbone.account.Account?,
    accounts: List<social.tbone.account.Account>,
    profiles: Map<String, ProfileContent>,
    onSwitch: (String) -> Unit,
    enabled: Boolean,
) {
    var showMenu by remember { mutableStateOf(false) }
    Text(
        text = "switch account",
        style = BonyType.tag.copy(color = BonyColors.Accent),
        modifier = Modifier.clickable(enabled = enabled) { showMenu = true }.padding(horizontal = 6.dp, vertical = 8.dp),
        maxLines = 1,
    )
    if (showMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMenu = false },
            containerColor = BonyColors.Surface,
            title = { Text("Switch account", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = {
                Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (accounts.isEmpty()) Text("No available accounts", style = BonyType.body.copy(color = BonyColors.TextMute))
                    accounts.forEach { acct ->
                        val selected = acct.pubkey == activeAccount?.pubkey
                        val profile = profiles[acct.pubkey]
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                onSwitch(acct.pubkey)
                                showMenu = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(pubkeyHex = acct.pubkey, profile = profile, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile?.bestName ?: acct.displayName ?: acct.pubkey.take(12),
                                    style = BonyType.body.copy(color = if (selected) BonyColors.Accent else BonyColors.Text),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(remember(acct.pubkey) { Phrase.wordsFor(acct.pubkey, 2).joinToString("·") },
                                    style = BonyType.caption.copy(color = BonyColors.TextMute))
                            }
                            if (selected) Text("current", style = BonyType.caption.copy(color = BonyColors.Accent))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showMenu = false }) { Text("close") }
            },
        )
    }
}

// ── Note preview dialog ───────────────────────────────────────────────────
@Composable
private fun NotePreviewDialog(
    content: String,
    pollEnabled: Boolean,
    pollOptions: List<String>,
    pollMultiple: Boolean,
    quoteEvents: List<Event>,
    isReply: Boolean,
    activeAccount: social.tbone.account.Account?,
    activeProfile: ProfileContent?,
    profiles: Map<String, ProfileContent>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.88f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 560.dp)
                    .clip(appCornerShape(MaterialTheme.shapes.medium))
                    .background(BonyColors.Surface)
                    .appBorder(1.dp, BonyColors.RuleStrong)
                    .clickable(enabled = false) {}
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "◎ PREVIEW",
                        style = BonyType.tag.copy(color = BonyColors.Accent),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "exactly as it will post",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(appCornerShape(MaterialTheme.shapes.small))
                            .background(BonyColors.SurfaceAlt)
                            .appBorder(1.dp, BonyColors.Rule)
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕ close", style = BonyType.tag.copy(color = BonyColors.TextMute))
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

                // Preview note card — mimics feed rendering
                val previewShape = appCornerShape(MaterialTheme.shapes.medium)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(previewShape)
                        .background(BonyColors.Bg, previewShape)
                        .appBorder(1.dp, BonyColors.Rule)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        if (activeAccount != null) {
                            UserAvatar(
                                pubkeyHex = activeAccount.pubkey,
                                profile = activeProfile,
                                size = 36.dp,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(BonyColors.SurfaceAlt),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val name = activeProfile?.bestName ?: activeAccount?.displayName ?: "You"
                                Text(
                                    text = name,
                                    style = BonyType.bodyDim.copy(color = BonyColors.Text),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (activeAccount != null) {
                                    Text(" · ", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                                    Text(
                                        text = remember(activeAccount.pubkey) {
                                            Phrase.wordsFor(activeAccount.pubkey, 2).joinToString("·")
                                        },
                                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = "now · preview",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                    }

                    if (isReply) {
                        Text(
                            text = "↳ replying to ${quoteEvents.firstOrNull()?.let { profiles[it.pubkey]?.bestName ?: it.pubkey.take(8) } ?: "thread"}",
                            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                        )
                    }

                    if (content.isNotBlank()) {
                        val parsed = remember(content) { parseNoteContent(content, profiles) }
                        if (parsed.text.isNotBlank()) {
                            NoteBodyText(
                                content = parsed.text,
                                style = BonyType.body.copy(color = BonyColors.Text),
                                maxLines = Int.MAX_VALUE,
                                expandable = false,
                                profiles = profiles,
                            )
                        }
                        if (parsed.mediaItems.isNotEmpty()) {
                            NoteMediaContent(mediaItems = parsed.mediaItems, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    if (pollEnabled) {
                        val nonBlank = pollOptions.map { it.trim() }.filter { it.isNotEmpty() }
                        if (nonBlank.isNotEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(appCornerShape(MaterialTheme.shapes.small))
                                    .background(BonyColors.SurfaceAlt)
                                    .appBorder(1.dp, BonyColors.Rule)
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("▭ poll", style = BonyType.tag.copy(color = BonyColors.Accent))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (pollMultiple) "multiple choice" else "single choice",
                                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    )
                                }
                                nonBlank.forEachIndexed { idx, label ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(appCornerShape(MaterialTheme.shapes.small))
                                            .background(BonyColors.Surface)
                                            .appBorder(1.dp, BonyColors.Rule)
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(BonyColors.RuleStrong)
                                                .appBorder(1.dp, BonyColors.Rule),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "${idx + 1}. $label",
                                            style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quoted notes (if quoting)
                    quoteEvents.forEach { ev ->
                        // Only show as quoted card if this event was referenced via q-tag / quote
                        // For reply preview we already showed header; still show quoted cards for quotes.
                        if (isReply && ev.id == quoteEvents.firstOrNull()?.id) {
                            // reply target already indicated; don't duplicate as quote card
                            return@forEach
                        }
                        QuotedNoteCard(
                            event = ev,
                            profile = profiles[ev.pubkey],
                            profiles = profiles,
                            fullContent = false,
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                Text(
                    text = "Tip: mentions like @npub… render as @name, links are cleaned, and quoted notes appear as cards. Poll options appear as shown above.",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
        }
    }
}
