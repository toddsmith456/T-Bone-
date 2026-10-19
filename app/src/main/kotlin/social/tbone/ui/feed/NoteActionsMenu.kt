package social.tbone.ui.feed

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.Nip56ReportReason
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType

private val PrettyEventJson: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    prettyPrintIndent = "  "
}

private data class ShareCardFile(
    val file: java.io.File,
    val mimeType: String,
    val label: String,
    val fallbackMessage: String? = null,
)

/** Compact, anchored note/reply action menu, following Amethyst's 3-dot action pattern. */
@Composable
fun NoteActionsMenu(
    event: Event,
    authorName: String? = null,
    authorPictureUrl: String? = null,
    modifier: Modifier = Modifier,
    viewModel: NoteActionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val moderationActionsEnabled by viewModel.moderationActionsEnabled.collectAsStateWithLifecycle()
    val bookmarkFolders by viewModel.bookmarkFolders.collectAsStateWithLifecycle()
    var expanded by remember(event.id) { mutableStateOf(false) }
    var bookmarkFoldersExpanded by remember(event.id) { mutableStateOf(false) }
    var rawJson by remember(event.id) { mutableStateOf<String?>(null) }
    var showRawJson by remember(event.id) { mutableStateOf(false) }
    var showReportDialog by remember(event.id) { mutableStateOf(false) }
    var showBlockDialog by remember(event.id) { mutableStateOf(false) }
    var selectedReportReason by remember(event.id) { mutableStateOf<Nip56ReportReason?>(null) }
    var reportComment by remember(event.id) { mutableStateOf("") }
    var creatingCard by remember(event.id) { mutableStateOf(false) }

    val nevent = remember(event.id, event.pubkey, event.kind) {
        runCatching { Nip19.hexToNevent(event.id, event.pubkey, event.kind) }
            .getOrElse { Nip19.hexToNote(event.id) }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "More note actions" },
            ) {
                val radius = 2.15.dp.toPx()
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val sideOffset = size.width * 0.22f
                val verticalOffset = size.height * 0.20f
                drawCircle(BonyColors.TextMute, radius, Offset(centerX, centerY - verticalOffset))
                drawCircle(BonyColors.TextMute, radius, Offset(centerX - sideOffset, centerY + verticalOffset))
                drawCircle(BonyColors.TextMute, radius, Offset(centerX + sideOffset, centerY + verticalOffset))
            }
        }

        val menuScheme = MaterialTheme.colorScheme.copy(
            surfaceContainer = BonyColors.Surface,
            surfaceContainerHigh = BonyColors.Surface,
            surfaceContainerLow = BonyColors.Surface,
        )
        MaterialTheme(colorScheme = menuScheme) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                NoteActionMenuItem(Icons.Outlined.Code, "View Raw JSON") {
                    expanded = false
                    rawJson = PrettyEventJson.encodeToString(Event.serializer(), event)
                    showRawJson = true
                }
                NoteActionMenuItem(Icons.Outlined.TextFields, "Copy note text") {
                    expanded = false
                    copyText(context, clipboard, event.content, "Note text")
                }
                NoteActionMenuItem(Icons.Outlined.BookmarkBorder, "Bookmark in…") {
                    expanded = false
                    bookmarkFoldersExpanded = true
                }
                NoteActionMenuItem(Icons.Outlined.Share, "Share Nostr event (nevent)") {
                    expanded = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "nostr:$nevent")
                    }
                    context.startSafely(Intent.createChooser(send, "Share Nostr event"))
                }
                NoteActionMenuItem(Icons.Outlined.Image, if (creatingCard) "Creating card…" else "Share as card") {
                    if (!creatingCard) {
                        expanded = false
                        creatingCard = true
                        scope.launch {
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    val video = NoteShareCardGenerator.firstVideo(event)
                                    val card = NoteShareCardGenerator.renderCard(
                                        context = context,
                                        event = event,
                                        authorName = authorName,
                                        authorPictureUrl = authorPictureUrl,
                                        animatedVideoUrl = video?.url,
                                    )
                                    if (video != null && card.videoSlot != null) {
                                        runCatching {
                                            ShareCardVideoGenerator.render(context, card, video)
                                        }.fold(
                                            onSuccess = { output ->
                                                ShareCardFile(output, "video/mp4", "Share playable video card")
                                            },
                                            onFailure = { error ->
                                                if (card.file.isFile && card.file.length() > 0L) {
                                                    ShareCardFile(
                                                        card.file,
                                                        "image/png",
                                                        "Share note card",
                                                        fallbackMessage = "Video card failed; sharing the still card instead.",
                                                    )
                                                } else {
                                                    throw error
                                                }
                                            },
                                        )
                                    } else {
                                        ShareCardFile(card.file, "image/png", "Share note card")
                                    }
                                }
                            }
                            result.onSuccess { output ->
                                output.fallbackMessage?.let { message ->
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        output.file,
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = output.mimeType
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        clipData = ClipData.newUri(context.contentResolver, "T-Bone note card", uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startSafely(
                                        Intent.createChooser(send, output.label).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "Could not share note card", Toast.LENGTH_SHORT).show()
                                }
                            }.onFailure {
                                Toast.makeText(context, it.message ?: "Could not create note card", Toast.LENGTH_LONG).show()
                            }
                            creatingCard = false
                        }
                    }
                }
                if (moderationActionsEnabled) {
                    NoteActionMenuItem(Icons.Outlined.Block, "Block poster") {
                        expanded = false
                        showBlockDialog = true
                    }
                    NoteActionMenuItem(Icons.Outlined.Flag, "Report note") {
                        expanded = false
                        showReportDialog = true
                    }
                }
                NoteActionMenuItem(Icons.Outlined.OpenInBrowser, "Open on Njump") {
                    expanded = false
                    context.startSafely(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://njump.me/$nevent")),
                    )
                }
                NoteActionMenuItem(Icons.Outlined.OpenInNew, "Open in another client") {
                    expanded = false
                    openInAnotherNostrClient(context, nevent)
                }
            }
            DropdownMenu(
                expanded = bookmarkFoldersExpanded,
                onDismissRequest = { bookmarkFoldersExpanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Text(
                    text = "save to folder",
                    style = BonyType.caption.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                bookmarkFolders.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder.name, style = BonyType.meta.copy(color = BonyColors.Text)) },
                        onClick = {
                            bookmarkFoldersExpanded = false
                            viewModel.saveBookmark(event, folder.id) { message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                if (bookmarkFolders.isEmpty()) {
                    Text(
                        text = "Loading folders…",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            containerColor = BonyColors.Surface,
            titleContentColor = BonyColors.Text,
            textContentColor = BonyColors.TextDim,
            title = { Text("Block poster?", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = {
                Text(
                    "This adds them to your active mute list. Online lists publish a NIP-51 update; offline lists stay on this device.",
                    style = BonyType.meta.copy(color = BonyColors.TextDim),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showBlockDialog = false
                    viewModel.blockAuthor(event.pubkey) { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }) { Text("BLOCK", color = BonyColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text("CANCEL", color = BonyColors.TextMute)
                }
            },
        )
    }

    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            containerColor = BonyColors.Surface,
            titleContentColor = BonyColors.Text,
            textContentColor = BonyColors.TextDim,
            title = { Text("Report note", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "NIP-56 reports are public. Choose a reason; an optional explanation is included in the report.",
                        style = BonyType.meta.copy(color = BonyColors.TextDim),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Nip56ReportReason.entries.forEach { reason ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReportReason = reason }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedReportReason == reason,
                                onClick = { selectedReportReason = reason },
                            )
                            Text(reason.label, style = BonyType.meta.copy(color = BonyColors.Text))
                        }
                    }
                    OutlinedTextField(
                        value = reportComment,
                        onValueChange = { reportComment = it },
                        label = { Text("Additional context") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedReportReason != null,
                    onClick = {
                        val reason = selectedReportReason ?: return@TextButton
                        showReportDialog = false
                        viewModel.reportNote(event, reason, reportComment) { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    },
                ) { Text("REPORT", color = BonyColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("CANCEL", color = BonyColors.TextMute)
                }
            },
        )
    }

    if (showRawJson) {
        val json = rawJson.orEmpty()
        AlertDialog(
            onDismissRequest = { showRawJson = false },
            containerColor = BonyColors.Surface,
            titleContentColor = BonyColors.Text,
            textContentColor = BonyColors.TextDim,
            title = {
                Column {
                    Text("Raw Nostr event", style = BonyType.body.copy(color = BonyColors.Text))
                    Text(
                        "kind ${event.kind} · ${event.id.take(12)}…",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
            },
            text = {
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 440.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BonyColors.Bg)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        Text(
                            text = json,
                            style = BonyType.caption.copy(
                                color = BonyColors.Text,
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    copyText(context, clipboard, json, "JSON")
                    showRawJson = false
                }) {
                    Text("COPY JSON", color = BonyColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawJson = false }) {
                    Text("CLOSE", color = BonyColors.TextMute)
                }
            },
        )
    }
}

@Composable
private fun NoteActionMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, style = BonyType.tag.copy(color = BonyColors.Text)) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BonyColors.TextMute,
                modifier = Modifier.size(18.dp),
            )
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 40.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    )
}

private fun copyText(
    context: Context,
    clipboard: ClipboardManager,
    text: String,
    description: String,
) {
    clipboard.setText(AnnotatedString(text))
    Toast.makeText(context, "$description copied", Toast.LENGTH_SHORT).show()
}

private fun openInAnotherNostrClient(context: Context, nevent: String) {
    val nostrIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nostr:$nevent"))
    val matches = runCatching {
        context.packageManager.queryIntentActivities(
            nostrIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
    }.getOrDefault(emptyList())
    val hasOtherClient = matches.any { info ->
        info.activityInfo?.packageName?.let { it != context.packageName } == true
    }
    if (!hasOtherClient) {
        Toast.makeText(context, "No other Nostr client found", Toast.LENGTH_SHORT).show()
        return
    }

    val chooser = Intent.createChooser(nostrIntent, "Open in another Nostr client")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val ownActivities = matches.mapNotNull { info ->
            info.activityInfo
                ?.takeIf { it.packageName == context.packageName }
                ?.let { ComponentName(it.packageName, it.name) }
        }.toTypedArray()
        if (ownActivities.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownActivities)
        }
    }
    context.startSafely(chooser)
}

private fun Context.startSafely(intent: Intent) {
    runCatching { startActivity(intent) }.onFailure {
        Toast.makeText(this, "No app could open this link", Toast.LENGTH_SHORT).show()
    }
}
