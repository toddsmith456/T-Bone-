package social.tbone.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.ProfileContent
import social.tbone.Tunables
import social.tbone.nostr.identity.Phrase
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.feed.NoteBodyText
import social.tbone.ui.feed.NoteMediaContent
import social.tbone.ui.feed.QuotedNoteCard
import social.tbone.ui.feed.parseNoteContent
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import social.tbone.ui.theme.appBorder
import social.tbone.ui.theme.appCornerShape
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext

private const val MENTION_PICKER_LIMIT = 10

@Composable
fun ComposeScreen(
    onClose: () -> Unit = {},
    onPublished: () -> Unit = {},
    viewModel: ComposeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val activeAccount by viewModel.activeAccount.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val mentionSearching by viewModel.mentionSearching.collectAsStateWithLifecycle()
    val mentionQuery by viewModel.mentionQuery.collectAsStateWithLifecycle()
    var fieldValue by remember(uiState.initialText) {
        mutableStateOf(TextFieldValue(text = uiState.initialText))
    }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var pickKind by remember { mutableStateOf<String?>(null) }
    val pickMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null && pickKind != null) {
            viewModel.attachMedia(uri, pickKind!!)
            pickKind = null
        }
    }
    var showBlossomChooser by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }

    // Keep local text in sync when the viewModel pre-fills the field
    // (e.g. a quote reference) after an async fetch.
    LaunchedEffect(uiState.initialText) {
        if (fieldValue.text.isBlank() && uiState.initialText.isNotBlank()) {
            fieldValue = TextFieldValue(text = uiState.initialText)
        }
    }

    val blossomUrlQueue = viewModel.uploadedMediaUrls
    LaunchedEffect(Unit) {
        blossomUrlQueue.collect { url ->
            val existing = fieldValue.text.trimEnd()
            val separator = if (existing.isEmpty()) "" else "\n"
            val nextText = if (existing.isEmpty()) url else "$existing$separator$url"
            fieldValue = TextFieldValue(
                text = nextText,
                selection = TextRange(nextText.length),
            )
        }
    }
    val uploadErrorFlow = viewModel.uploadErrors
    LaunchedEffect(Unit) {
        uploadErrorFlow.collect { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val mentionPattern = remember { Regex("""@([^\s@]{1,30})$""") }
    val mentionMatch = remember(fieldValue.text, fieldValue.selection) {
        if (fieldValue.selection.collapsed) {
            val before = fieldValue.getTextBeforeSelection(fieldValue.text.length)
            mentionPattern.find(before)?.let { match ->
                val name = match.groupValues[1]
                // Don't trigger for an email-like "@name." that ends with a
                // dot — e.g. typing "hi @bob." before finishing the word would
                // re-open the mention sheet for "bob." after every keystroke.
                // Only trigger when the @ is at the start of text or preceded by
                // whitespace/punctuation that actually delimits a mention.
                val atIndex = match.range.first
                val prev = before.getOrNull(atIndex - 1)
                val isolated = atIndex == 0 || prev == null || prev.isWhitespace() || prev in setOf('"', '(', ',', ';', ':', '\'', '(', '[', '{', '\n')
                if (!isolated || name.endsWith(".")) null else name
            }
        } else null
    }
    LaunchedEffect(mentionMatch) { viewModel.setMentionQuery(mentionMatch) }

    fun String.resolveMentions(): String {
        val allMentions = Regex(\"\"\"@(npub1[a-z0-9]{58,})\"\"\").findAll(fieldValue.text).map { it.groupValues[1] }.toList()
        val mentionMap = mutableMapOf<String, String>()
        allMentions.forEach { npub ->
            val hex = Nip19.npubToHex(npub) ?: return@forEach
            val label = profiles[hex]?.bestName ?: npub.take(12)
            mentionMap[npub] = label
        }
        var out = fieldValue.text
        mentionMap.forEach { (npub, label) ->
            out = out.replace(\"@$npub\", \"@$label\")
        }
        // Strip tracking params from links so the relay text is canonical
        out = out.replace(Regex(\"\"\"https?://\\S+\"\"\")) { m ->
            social.tbone.util.LinkCleaner.stripTrackingParams(m.value)
        }
        return out
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ── Title row (close + title + avatar switch + SEND) ─────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = \"×\",
                style = BonyType.title.copy(color = BonyColors.TextMute),
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(8.dp),
            )
            val title = when {
                uiState.quoteFolderEvents.isNotEmpty() -> \"Quote folder\"
                uiState.quoteToEvent != null -> \"Quote note\"
                uiState.replyToEvent != null -> \"Reply\"
                else -> \"New note\"
            }
            Text(
                text = title,
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                maxLines = 1,
            )
            // Compact avatar account switch beside SEND
            ComposeAvatarSwitcher(
                activeAccount = activeAccount,
                accounts = accounts,
                profiles = profiles,
                onSwitch = viewModel::switchAccount,
            )
            Spacer(Modifier.width(8.dp))
            val canSend = fieldValue.text.isNotBlank() && !uiState.isPublishing
            val sendShape = appCornerShape(MaterialTheme.shapes.small)
            Box(
                modifier = Modifier
                    .clip(sendShape)
                    .background(
                        if (canSend) BonyColors.Accent else BonyColors.SurfaceAlt,
                        sendShape,
                    )
                    .border(1.dp, if (canSend) BonyColors.Accent else BonyColors.Rule, sendShape)
                    .clickable(enabled = canSend) {
                        val trimmed = fieldValue.text.trim()
                        if (trimmed.isBlank()) return@clickable
                        viewModel.publish(
                            content = trimmed.resolveMentions(),
                            onSuccess = onPublished,
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = BonyColors.Bg,
                        strokeWidth = 1.5.dp,
                    )
                } else {
                    Text(
                        text = \"SEND\",
                        style = BonyType.button.copy(
                            color = if (canSend) BonyColors.Bg else BonyColors.TextMute,
                        ),
                    )
                }
            }
        }

        // ── Quote / Reply Context Card (expandable) ──────────────────────────
        uiState.replyToEvent?.let { event ->
            ComposeContextCard(
                label = \"Replying to\",
                event = event,
                profiles = profiles,
            )
        }
        uiState.quoteToEvent?.let { event ->
            ComposeContextCard(
                label = \"Quoting\",
                event = event,
                profiles = profiles,
            )
        }
        if (uiState.quoteFolderEvents.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(appCornerShape(MaterialTheme.shapes.medium))
                    .background(BonyColors.Surface)
                    .appBorder(1.dp, BonyColors.Rule)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = \"Quoting ${uiState.quoteFolderEvents.size} notes from folder\",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                )
                uiState.quoteFolderEvents.take(3).forEach { event ->
                    ComposeContextCard(
                        label = \"·\",
                        event = event,
                        profiles = profiles,
                        compact = true,
                    )
                }
                if (uiState.quoteFolderEvents.size > 3) {
                    Text(
                        text = \"+${uiState.quoteFolderEvents.size - 3} more\",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                }
            }
        }

        if (uiState.error != null) {
            Text(
                text = uiState.error!!,
                style = BonyType.bodyDim.copy(color = BonyColors.Danger),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(appCornerShape(MaterialTheme.shapes.small))
                    .background(BonyColors.Danger.copy(alpha = 0.08f))
                    .appBorder(1.dp, BonyColors.Danger.copy(alpha = 0.35f))
                    .padding(10.dp),
            )
        }

        // ── Text editor ──────────────────────────────────────────────────────
        val mentionVisual = remember(fieldValue.text, profiles) {
            buildMentionTransformation(fieldValue.text, profiles)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(appCornerShape(MaterialTheme.shapes.medium))
                .background(BonyColors.Surface)
                .appBorder(1.dp, BonyColors.Rule)
                .padding(12.dp),
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { fieldValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                cursorBrush = SolidColor(BonyColors.Accent),
                visualTransformation = mentionVisual,
                decorationBox = { inner ->
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (fieldValue.text.isEmpty()) {
                            Text(
                                text = \"What's happening? @ to mention, # for topics\",
                                style = BonyType.body.copy(color = BonyColors.TextMute),
                            )
                        }
                        inner()
                    }
                },
            )
            // Mention picker dropdown (below the text)
            if (mentionMatch != null) {
                val query = mentionMatch
                val suggestions = mentionSuggestions.take(MENTION_PICKER_LIMIT)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(BonyColors.SurfaceAlt)
                        .appBorder(1.dp, BonyColors.Rule),
                ) {
                    if (mentionSearching && suggestions.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = BonyColors.Accent,
                                strokeWidth = 1.5.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "searching \"$query\"…",
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                            )
                        }
                    }
                    suggestions.forEach { match ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val before = fieldValue.getTextBeforeSelection(fieldValue.text.length)
                                    val atIndex = before.lastIndexOf(\"@$query\")
                                    if (atIndex >= 0) {
                                        val npub = Nip19.hexToNpub(match.pubkey)
                                        val beforeAt = fieldValue.text.substring(0, atIndex)
                                        val after = fieldValue.text.substring(atIndex + 1 + query.length)
                                        val inserted = \"@$npub \"
                                        val nextText = beforeAt + inserted + after
                                        val nextCursor = beforeAt.length + inserted.length
                                        fieldValue = TextFieldValue(
                                            text = nextText,
                                            selection = TextRange(nextCursor),
                                        )
                                        viewModel.setMentionQuery(null)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(
                                pubkeyHex = match.pubkey,
                                profile = match.profile,
                                size = 28.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val name = match.profile?.bestName ?: match.pubkey.take(12)
                                Text(
                                    text = name,
                                    style = BonyType.bodyDim.copy(color = BonyColors.Text),
                                    maxLines = 1,
                                )
                                val phrase = remember(match.pubkey) {
                                    Phrase.wordsFor(match.pubkey, 2).joinToString(\"·\")
                                }
                                Text(
                                    text = phrase,
                                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                                    maxLines = 1,
                                )
                            }
                            Text(
                                text = \"↗\",
                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BonyColors.Rule),
                        )
                    }
                    if (!mentionSearching && suggestions.isEmpty() && query.isNotEmpty()) {
                        Text(
                            text = "no matches for \"$query\"",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            // Toolbar: mention, poll, preview, media
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // @ mention helper
                Box(
                    modifier = Modifier
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(BonyColors.SurfaceAlt)
                        .appBorder(1.dp, BonyColors.Rule)
                        .clickable {
                            val cursor = fieldValue.selection.start.coerceAtLeast(0)
                            val before = fieldValue.text.substring(0, cursor.coerceAtMost(fieldValue.text.length))
                            val after = fieldValue.text.substring(cursor.coerceAtMost(fieldValue.text.length))
                            val nextText = before + \"@\" + after
                            fieldValue = TextFieldValue(
                                text = nextText,
                                selection = TextRange(cursor + 1),
                            )
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(\"+@\", style = BonyType.tag.copy(color = BonyColors.TextMute))
                }
                // Poll toggle
                Box(
                    modifier = Modifier
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(if (uiState.pollEnabled) BonyColors.AccentBg else BonyColors.SurfaceAlt)
                        .appBorder(1.dp, if (uiState.pollEnabled) BonyColors.Accent else BonyColors.Rule)
                        .clickable { viewModel.togglePoll() }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (uiState.pollEnabled) \"poll ✓\" else \"poll\",
                        style = BonyType.tag.copy(
                            color = if (uiState.pollEnabled) BonyColors.Accent else BonyColors.TextMute,
                        ),
                    )
                }
                // Preview button
                Box(
                    modifier = Modifier
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(BonyColors.SurfaceAlt)
                        .appBorder(1.dp, BonyColors.Rule)
                        .clickable { showPreview = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(\"◎ preview\", style = BonyType.tag.copy(color = BonyColors.TextDim))
                }
                Spacer(Modifier.weight(1f))
                // Media pickers: image / video / audio
                val uploading = uiState.isUploading
                MediaPickerButton(label = \"🖼\", enabled = !uploading) {
                    pickKind = \"image\"
                    pickMediaLauncher.launch(\"image/*\")
                }
                MediaPickerButton(label = \"▶\", enabled = !uploading) {
                    pickKind = \"video\"
                    pickMediaLauncher.launch(\"video/*\")
                }
                MediaPickerButton(label = \"♫\", enabled = !uploading) {
                    pickKind = \"audio\"
                    pickMediaLauncher.launch(\"audio/*\")
                }
                if (uploading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            color = BonyColors.Accent,
                            strokeWidth = 1.5.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when (uiState.uploadingKind) {
                                \"video\" -> \"video…\"
                                \"audio\" -> \"audio…\"
                                else -> \"image…\"
                            },
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                        )
                    }
                }
            }
            // Blossom server chooser row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = \"via ${uiState.blossomServer ?: \"default\"}\",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    modifier = Modifier
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(BonyColors.SurfaceAlt)
                        .appBorder(1.dp, BonyColors.Rule)
                        .clickable { showBlossomChooser = !showBlossomChooser }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (showBlossomChooser) \"close\" else \"server ▾\",
                        style = BonyType.tag.copy(color = BonyColors.TextMute),
                    )
                }
            }
            if (showBlossomChooser) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(appCornerShape(MaterialTheme.shapes.small))
                        .background(BonyColors.SurfaceAlt)
                        .appBorder(1.dp, BonyColors.Rule),
                ) {
                    BlossomServerRow(
                        label = \"default (random from pool)\",
                        selected = uiState.blossomServer == null,
                        onClick = {
                            viewModel.setBlossomServer(null)
                            showBlossomChooser = false
                        },
                    )
                    uiState.blossomServers.forEach { server ->
                        BlossomServerRow(
                            label = server,
                            selected = uiState.blossomServer == server,
                            onClick = {
                                viewModel.setBlossomServer(server)
                                showBlossomChooser = false
                            },
                        )
                    }
                }
            }
        }

        // ── Poll builder (shown when pollEnabled) ──────────────────────────
        if (uiState.pollEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(appCornerShape(MaterialTheme.shapes.medium))
                    .background(BonyColors.Surface)
                    .appBorder(1.dp, BonyColors.Rule)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = \"Poll options\",
                        style = BonyType.tag.copy(color = BonyColors.TextDim),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (uiState.pollMultiple) \"multiple\" else \"single\",
                        style = BonyType.tag.copy(color = BonyColors.Accent),
                        modifier = Modifier
                            .clip(appCornerShape(MaterialTheme.shapes.small))
                            .background(BonyColors.AccentBg)
                            .appBorder(1.dp, BonyColors.Accent)
                            .clickable { viewModel.togglePollType() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                uiState.pollOptions.forEachIndexed { index, option ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = \"${index + 1}.\",
                            style = BonyType.meta.copy(color = BonyColors.TextMute),
                            modifier = Modifier.width(20.dp),
                        )
                        BasicTextField(
                            value = option,
                            onValueChange = { viewModel.setPollOption(index, it) },
                            modifier = Modifier.weight(1f),
                            textStyle = BonyType.bodyDim.copy(color = BonyColors.Text),
                            cursorBrush = SolidColor(BonyColors.Accent),
                            singleLine = true,
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(appCornerShape(MaterialTheme.shapes.small))
                                        .background(BonyColors.Bg)
                                        .appBorder(1.dp, BonyColors.Rule)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                ) {
                                    if (option.isEmpty()) {
                                        Text(
                                            text = \"Option ${index + 1}\",
                                            style = BonyType.bodyDim.copy(color = BonyColors.TextMute),
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                        if (uiState.pollOptions.size > 2) {
                            Text(
                                text = \" ×\",
                                style = BonyType.bodyDim.copy(color = BonyColors.Danger),
                                modifier = Modifier
                                    .clickable { viewModel.removePollOption(index) }
                                    .padding(start = 8.dp),
                            )
                        }
                    }
                }
                if (uiState.pollOptions.size < Tunables.MAX_POLL_OPTIONS) {
                    Text(
                        text = \"+ add option\",
                        style = BonyType.tag.copy(color = BonyColors.Accent),
                        modifier = Modifier
                            .clip(appCornerShape(MaterialTheme.shapes.small))
                            .background(BonyColors.AccentBg)
                            .appBorder(1.dp, BonyColors.Accent)
                            .clickable { viewModel.addPollOption() }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    // ── Preview popup ──────────────────────────────────────────────────────
    if (showPreview) {
        val trimmed = fieldValue.text.trim()
        val resolved = trimmed.resolveMentions()
        val finalContent = viewModel.buildPreviewContent(resolved.trim())
        NotePreviewDialog(
            content = finalContent.ifBlank { resolved.ifBlank { \"(empty note)\" } },
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

@Composable
private fun MediaPickerButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(appCornerShape(MaterialTheme.shapes.small))
            .background(if (enabled) BonyColors.SurfaceAlt else BonyColors.Bg)
            .appBorder(1.dp, BonyColors.Rule)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = BonyType.tag.copy(
                color = if (enabled) BonyColors.TextDim else BonyColors.TextMute.copy(alpha = 0.5f),
            ),
        )
    }
}

// ── Compact avatar account switcher beside SEND ────────────────────────────
@Composable
private fun ComposeAvatarSwitcher(
    activeAccount: social.tbone.account.Account?,
    accounts: List<social.tbone.account.Account>,
    profiles: Map<String, ProfileContent>,
    onSwitch: (String) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    val activeProfile = activeAccount?.let { profiles[it.pubkey] }

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(appCornerShape(MaterialTheme.shapes.small))
                .background(BonyColors.SurfaceAlt)
                .appBorder(1.dp, BonyColors.Rule)
                .clickable { if (accounts.size > 1) showMenu = true }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            if (activeAccount != null) {
                UserAvatar(
                    pubkeyHex = activeAccount.pubkey,
                    profile = activeProfile,
                    size = 22.dp,
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    val name = activeProfile?.bestName ?: activeAccount.pubkey.take(8)
                    Text(
                        text = name,
                        style = BonyType.tag.copy(color = BonyColors.TextDim),
                        maxLines = 1,
                    )
                    if (accounts.size > 1) {
                        Text(
                            text = \"switch ▾\",
                            style = BonyType.caption.copy(color = BonyColors.TextMute),
                        )
                    }
                }
            } else {
                Text(\"no account\", style = BonyType.tag.copy(color = BonyColors.TextMute))
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(BonyColors.Surface),
        ) {
            accounts.forEach { acct ->
                val isActive = acct.pubkey == activeAccount?.pubkey
                val p = profiles[acct.pubkey]
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(pubkeyHex = acct.pubkey, profile = p, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.widthIn(max = 160.dp)) {
                                Text(
                                    text = p?.bestName ?: acct.displayName ?: acct.pubkey.take(10),
                                    style = BonyType.bodyDim.copy(
                                        color = if (isActive) BonyColors.Accent else BonyColors.Text,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = remember(acct.pubkey) { Phrase.wordsFor(acct.pubkey, 2).joinToString(\"·\") },
                                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                                    maxLines = 1,
                                )
                            }
                            if (isActive) {
                                Spacer(Modifier.width(8.dp))
                                Text(\"●\", style = BonyType.tag.copy(color = BonyColors.Accent))
                            }
                        }
                    },
                    onClick = {
                        onSwitch(acct.pubkey)
                        showMenu = false
                    },
                )
            }
        }
    }
}

// ── Expandable context card with Read more ────────────────────────────────
@Composable
private fun ComposeContextCard(
    label: String,
    event: Event,
    profiles: Map<String, ProfileContent> = emptyMap(),
    compact: Boolean = false,
) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    val isLong = event.content.length > 180 || event.content.lines().size > 4 || event.content.length > 220
    val displayText = if (expanded || !isLong) event.content else event.content.take(200).trimEnd() + \"…\"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(appCornerShape(MaterialTheme.shapes.medium))
            .background(BonyColors.Surface)
            .appBorder(1.dp, BonyColors.Rule)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = BonyType.tag.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.width(6.dp))
            UserAvatar(
                pubkeyHex = event.pubkey,
                profile = profiles[event.pubkey],
                size = if (compact) 18.dp else 22.dp,
            )
            Spacer(Modifier.width(6.dp))
            val name = profiles[event.pubkey]?.bestName ?: event.pubkey.take(12)
            Text(
                text = name,
                style = BonyType.metaDim.copy(color = BonyColors.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeAgo(event.createdAt),
                style = BonyType.caption.copy(color = BonyColors.TextMute),
            )
        }
        Text(
            text = displayText,
            style = BonyType.bodyDim.copy(color = BonyColors.TextDim),
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (isLong) {
            Text(
                text = if (expanded) \"Show less\" else \"Read more →\",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .clip(appCornerShape(MaterialTheme.shapes.small))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
            )
        }
    }
}

private fun timeAgo(epochSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val d = now - epochSeconds
    return when {
        d < 60 -> \"now\"
        d < 3600 -> \"${d / 60}m ago\"
        d < 86400 -> \"${d / 3600}h ago\"
        else -> \"${d / 86400}d ago\"
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
                        text = \"◎ PREVIEW\",
                        style = BonyType.tag.copy(color = BonyColors.Accent),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = \"exactly as it will post\",
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
                        Text(\"✕ close\", style = BonyType.tag.copy(color = BonyColors.TextMute))
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
                                val name = activeProfile?.bestName ?: activeAccount?.displayName ?: \"You\"
                                Text(
                                    text = name,
                                    style = BonyType.bodyDim.copy(color = BonyColors.Text),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (activeAccount != null) {
                                    Text(\" · \", style = BonyType.metaDim.copy(color = BonyColors.TextMute))
                                    Text(
                                        text = remember(activeAccount.pubkey) {
                                            Phrase.wordsFor(activeAccount.pubkey, 2).joinToString(\"·\")
                                        },
                                        style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Text(
                                text = \"now · preview\",
                                style = BonyType.caption.copy(color = BonyColors.TextMute),
                            )
                        }
                    }

                    if (isReply) {
                        Text(
                            text = \"↳ replying to ${quoteEvents.firstOrNull()?.let { profiles[it.pubkey]?.bestName ?: it.pubkey.take(8) } ?: \"thread\"}\",
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
                                    Text(\"▭ poll\", style = BonyType.tag.copy(color = BonyColors.Accent))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (pollMultiple) \"multiple choice\" else \"single choice\",
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
                                            text = \"${idx + 1}. $label\",
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
                    text = \"Tip: mentions like @npub… render as @name, links are cleaned, and quoted notes appear as cards. Poll options appear as shown above.\",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                )
            }
        }
    }
}

@Composable
private fun BlossomServerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) BonyColors.AccentBg else BonyColors.SurfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Text(\"●\", style = BonyType.meta.copy(color = BonyColors.Accent))
            Spacer(Modifier.width(8.dp))
        } else {
            Text(\"○\", style = BonyType.meta.copy(color = BonyColors.TextMute))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = BonyType.meta.copy(
                color = if (selected) BonyColors.Accent else BonyColors.TextDim,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
}

private fun buildMentionTransformation(
    text: String,
    profiles: Map<String, ProfileContent>,
): VisualTransformation {
    val npubRegex = Regex(\"\"\"npub1[a-z0-9]{58,}\"\"\")
    val spans = mutableListOf<SpanRange>()
    npubRegex.findAll(text).forEach { match ->
        val npub = match.value
        val hex = Nip19.npubToHex(npub) ?: return@forEach
        val label = profiles[hex]?.bestName ?: npub.take(10)
        spans.add(SpanRange(match.range.first, match.range.last + 1, label))
    }
    if (spans.isEmpty()) return VisualTransformation.None
    return VisualTransformation { _ ->
        val out = buildAnnotatedString {
            var cursor = 0
            spans.sortedBy { it.start }.forEach { span ->
                append(text.substring(cursor, span.start))
                val start = length
                append(span.label)
                addStyle(SpanStyle(color = BonyColors.Accent), start, length)
                cursor = span.end
            }
            append(text.substring(cursor))
        }
        TransformedText(out, OffsetMapping.Identity)
    }
}

private data class SpanRange(val start: Int, val end: Int, val label: String)

private fun String.getTextBeforeSelection(length: Int): String {
    // Used only to extract text before cursor; TextFieldValue handles selection
    return this.take(length.coerceAtMost(this.length))
}

private fun TextFieldValue.getTextBeforeSelection(maxLength: Int): String {
    val cursor = selection.start.coerceAtLeast(0)
    val end = cursor.coerceAtMost(text.length)
    val start = (end - maxLength).coerceAtLeast(0)
    return text.substring(start, end)
}
