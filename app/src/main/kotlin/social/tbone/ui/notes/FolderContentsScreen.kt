package social.tbone.ui.notes

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import social.tbone.notes.NoteKind
import social.tbone.notes.NoteUi
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * A folder's own screen — exactly like the main notes screen, but only the
 * notes and checklists inside this folder are shown, and anything new created
 * here is automatically part of the folder.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderContentsScreen(
    folderId: String,
    folderName: String,
    onBack: () -> Unit,
    onNewNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    onNewChecklist: () -> Unit,
    onOpenChecklist: (String) -> Unit,
    onShare: (String) -> Unit,
    viewModel: NotesViewModel = hiltViewModel(),
) {
    val allNotes by viewModel.notes.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var actionTarget by remember { mutableStateOf<NoteUi?>(null) }

    LaunchedEffect(error) {
        if (error != null) {
            delay(2_500)
            viewModel.clearError()
        }
    }

    // Only this folder's notes, pinned first then manual order.
    val notes = remember(allNotes, folderId) {
        allNotes.filter { it.folderId == folderId }
            .sortedWith(compareByDescending<NoteUi> { it.pinned }.thenBy { it.sortOrder })
    }

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
                modifier = Modifier.clickable { onBack() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = folderName,
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BonyColors.Danger.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(text = error.orEmpty(), style = BonyType.meta.copy(color = BonyColors.Danger))
            }
        }

        if (notes.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "no notes in this folder", style = BonyType.body.copy(color = BonyColors.TextDim))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "tap + to add a note or checklist here",
                        style = BonyType.meta.copy(color = BonyColors.TextMute),
                    )
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .border(1.dp, BonyColors.Accent)
                            .clickable { onNewNote() }
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text("+ WRITE A NOTE", style = BonyType.button.copy(color = BonyColors.Accent))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                items(notes, key = { it.id }) { note ->
                    FolderNoteRow(
                        note = note,
                        onClick = {
                            when (note.kind) {
                                NoteKind.NOTE -> onOpenNote(note.id)
                                NoteKind.CHECKLIST -> onOpenChecklist(note.id)
                            }
                        },
                        onLongClick = { actionTarget = note },
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    // Floating + with a two-option popup: new note / new checklist (both in folder).
    var fabMenu by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = 12.dp)
                .border(1.dp, BonyColors.AccentDim)
                .background(BonyColors.Surface)
                .clickable { fabMenu = true }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(text = "+", style = BonyType.button.copy(color = BonyColors.Accent))
        }
    }
    if (fabMenu) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg.copy(alpha = 0.6f))
                .clickable { fabMenu = false },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 12.dp)
                    .border(1.dp, BonyColors.RuleStrong)
                    .background(BonyColors.Surface)
                    .clickable(enabled = false) {}
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = "+ new note",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fabMenu = false; onNewNote() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                Text(
                    text = "+ new checklist",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fabMenu = false; onNewChecklist() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }

    // Long-press popup (copy / share / pin / move / delete).
    actionTarget?.let { note ->
        FolderNoteActionsOverlay(
            note = note,
            onDismiss = { actionTarget = null },
            onCopy = { copyToClipboard2(context, note.shareText()); actionTarget = null },
            onShare = { onShare(note.shareText()); actionTarget = null },
            onPinToggle = { viewModel.pinToggle(note.id); actionTarget = null },
            onMoveUp = { viewModel.moveUp(note.id); actionTarget = null },
            onMoveDown = { viewModel.moveDown(note.id); actionTarget = null },
            onDelete = { viewModel.delete(note.id); actionTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderNoteRow(note: NoteUi, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (note.pinned) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = "pinned",
                tint = BonyColors.Accent,
                modifier = Modifier.height(14.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Text(
                text = if (note.kind == NoteKind.NOTE) "✎" else "☑",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            val checklist = note.checklist()
            Text(
                text = note.displayTitle(),
                style = BonyType.body.copy(color = BonyColors.Text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val subtitle = when {
                note.kind == NoteKind.CHECKLIST && checklist != null ->
                    "${checklist.doneCount}/${checklist.items.size} done · ${note.updatedAt.formatNoteDate()}"
                else -> note.updatedAt.formatNoteDate()
            }
            Text(
                text = subtitle,
                style = BonyType.metaDim.copy(color = BonyColors.TextMute),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderNoteActionsOverlay(
    note: NoteUi,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPinToggle: () -> Unit,
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
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = (if (note.kind == NoteKind.NOTE) "note" else "checklist") +
                    if (note.pinned) " · pinned" else "",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                text = "copy",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onCopy)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = "share outside of app",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onShare)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = if (note.pinned) "unpin" else "pin",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onPinToggle)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = "move up",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onMoveUp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                text = "move down",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onMoveDown)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
            Text(
                text = "delete",
                style = BonyType.body.copy(color = BonyColors.Danger),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private fun copyToClipboard2(context: android.content.Context, text: String) {
    runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("note", text))
    }
}

private fun Long.formatNoteDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d · HH:mm"))
