package social.tbone.ui.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.OutlinedTextField
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
import social.tbone.notes.NoteFolderUi
import social.tbone.notes.NoteKind
import social.tbone.notes.NoteUi
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

private sealed interface FabAction {
    data object NewNote : FabAction
    data object NewChecklist : FabAction
    data object NewFolder : FabAction
}

/**
 * The Notes tool — encrypted on-device notes, checklists and folders.
 * A single floating + button offers New Note / New Checklist / New Folder.
 * Folders can hold any number of notes + checklists; the long-press popup
 * moves items into folders, pins, reorders, copies, shares or deletes them.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesScreen(
    onBack: () -> Unit,
    onNewNote: () -> Unit,
    onOpenNote: (String) -> Unit,
    onNewChecklist: () -> Unit,
    onOpenChecklist: (String) -> Unit,
    onOpenFolder: (String, String) -> Unit,
    onInfo: () -> Unit,
    onShare: (String) -> Unit,
    onPublish: (String) -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val encryptionVerified by viewModel.encryptionVerified.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var fabMenu by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<NoteUi?>(null) }
    var folderTarget by remember { mutableStateOf<NoteFolderUi?>(null) }
    var chooseFolderFor by remember { mutableStateOf<String?>(null) }
    var renameFolderFor by remember { mutableStateOf<NoteFolderUi?>(null) }

    LaunchedEffect(error) {
        if (error != null) {
            delay(2_500)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BonyColors.Bg)
                .statusBarsPadding(),
        ) {
            // ── Top bar ─────────────────────────────────────────────────────────
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
                    text = "notes",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (encryptionVerified) "ⓘ encryption" else "ⓘ encryption ⚠",
                    style = BonyType.tag.copy(
                        color = if (encryptionVerified) BonyColors.Accent else BonyColors.Danger,
                    ),
                    modifier = Modifier
                        .border(1.dp, if (encryptionVerified) BonyColors.AccentDim else BonyColors.Danger)
                        .clickable { onInfo() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
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

            if (notes.isEmpty() && folders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "no notes yet",
                            style = BonyType.body.copy(color = BonyColors.TextDim),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "encrypted on-device notes + checklists + folders",
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
                    // Pinned first (the very top), regardless of folder.
                    val pinned = notes.filter { it.pinned }.sortedBy { it.sortOrder }
                    items(pinned, key = { "n_${it.id}" }) { note ->
                        NoteRow(note, onClick = {
                            when (note.kind) {
                                NoteKind.NOTE -> onOpenNote(note.id)
                                NoteKind.CHECKLIST -> onOpenChecklist(note.id)
                            }
                        }, onLongClick = { noteTarget = note })
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                    }
                    // Folders in order, each with its unpinned children.
                    val folderList = folders.sortedWith(
                        compareByDescending<NoteFolderUi> { it.pinned }.thenBy { it.sortOrder }.thenBy { it.name },
                    )
                    folderList.forEach { folder ->
                        item(key = "f_${folder.id}") {
                            FolderRow(
                                folder = folder,
                                count = notes.count { it.folderId == folder.id },
                                onToggle = { onOpenFolder(folder.id, folder.name) },
                                onLongClick = { folderTarget = folder },
                            )
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                        }
                    }
                    // Un-filed unpinned notes.
                    val unfiled = notes.filter { !it.pinned && it.folderId.isNullOrBlank() }
                        .sortedBy { it.sortOrder }
                    items(unfiled, key = { "u_${it.id}" }) { note ->
                        NoteRow(note, onClick = {
                            when (note.kind) {
                                NoteKind.NOTE -> onOpenNote(note.id)
                                NoteKind.CHECKLIST -> onOpenChecklist(note.id)
                            }
                        }, onLongClick = { noteTarget = note })
                        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }

        // ── Floating + button (bottom-right) ───────────────────────────────────
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

    // ── + popup ────────────────────────────────────────────────────────────────
    if (fabMenu) {
        FabMenuOverlay(
            onDismiss = { fabMenu = false },
            onNewNote = { fabMenu = false; onNewNote() },
            onNewChecklist = { fabMenu = false; onNewChecklist() },
            onNewFolder = { fabMenu = false; newFolderDialog = true },
        )
    }

    // ── New-folder dialog ──────────────────────────────────────────────────────
    if (newFolderDialog) {
        NameDialog(
            title = "new folder",
            initial = "",
            confirmLabel = "create",
            onDismiss = { newFolderDialog = false },
            onConfirm = { name ->
                newFolderDialog = false
                if (name.isNotBlank()) viewModel.addFolder(name)
            },
        )
    }

    // ── Rename-folder dialog ───────────────────────────────────────────────────
    renameFolderFor?.let { folder ->
        NameDialog(
            title = "rename folder",
            initial = folder.name,
            confirmLabel = "rename",
            onDismiss = { renameFolderFor = null },
            onConfirm = { name ->
                renameFolderFor = null
                if (name.isNotBlank()) viewModel.renameFolder(folder.id, name)
            },
        )
    }

    // ── Note long-press popup ──────────────────────────────────────────────────
    noteTarget?.let { note ->
        NoteActionsOverlay(
            note = note,
            onDismiss = { noteTarget = null },
            onCopy = { copyToClipboard(context, note.shareText()); noteTarget = null },
            onShare = { onShare(note.shareText()); noteTarget = null },
            onPublish = { onPublish(note.shareText()); noteTarget = null },
            onPinToggle = { viewModel.pinToggle(note.id); noteTarget = null },
            onMoveUp = { viewModel.moveUp(note.id); noteTarget = null },
            onMoveDown = { viewModel.moveDown(note.id); noteTarget = null },
            onAddToFolder = { chooseFolderFor = note.id; noteTarget = null },
            onDelete = { viewModel.delete(note.id); noteTarget = null },
        )
    }

    // ── Folder long-press popup ────────────────────────────────────────────────
    folderTarget?.let { folder ->
        FolderActionsOverlay(
            folder = folder,
            onDismiss = { folderTarget = null },
            onRename = { folderTarget = null; renameFolderFor = folder },
            onPinToggle = { viewModel.pinFolderToggle(folder.id); folderTarget = null },
            onMoveUp = { viewModel.folderMoveUp(folder.id); folderTarget = null },
            onMoveDown = { viewModel.folderMoveDown(folder.id); folderTarget = null },
            onDelete = { viewModel.deleteFolder(folder.id); folderTarget = null },
        )
    }

    // ── Folder chooser (add to folder) ────────────────────────────────────────
    chooseFolderFor?.let { noteId ->
        FolderChooserOverlay(
            folders = folders,
            onDismiss = { chooseFolderFor = null },
            onPick = { folderId ->
                viewModel.moveToFolder(noteId, folderId)
                chooseFolderFor = null
            },
        )
    }
}

/** The floating + popup — three actions in the same corner. */
@Composable
private fun FabMenuOverlay(
    onDismiss: () -> Unit,
    onNewNote: () -> Unit,
    onNewChecklist: () -> Unit,
    onNewFolder: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.6f))
            .clickable(onClick = onDismiss),
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
            FabMenuAction("+ new note", onNewNote)
            FabMenuAction("+ new checklist", onNewChecklist)
            FabMenuAction("+ new folder", onNewFolder)
        }
    }
}

@Composable
private fun FabMenuAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = BonyType.body.copy(color = BonyColors.Text),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** Simple name entry dialog (new/rename folder). */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
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
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(text = title, style = BonyType.body.copy(color = BonyColors.Text))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
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
                    text = confirmLabel,
                    style = BonyType.tag.copy(
                        color = if (name.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier
                        .border(1.dp, if (name.isNotBlank()) BonyColors.AccentDim else BonyColors.Rule)
                        .clickable(enabled = name.isNotBlank()) { onConfirm(name) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Folder header row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderRow(
    folder: NoteFolderUi,
    count: Int,
    onToggle: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onLongClick)
            .background(BonyColors.SurfaceAlt)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folder.pinned) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = "pinned folder",
                tint = BonyColors.Accent,
                modifier = Modifier.height(14.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = folder.name,
            style = BonyType.body.copy(color = BonyColors.Accent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count",
            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
        )
    }
}

/** A note/checklist row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteRow(
    note: NoteUi,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (indented) 26.dp else 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
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
            val title = note.displayTitle()
            LinkSegmentedText(
                text = title,
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth(),
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

/** Long-press popup for a note/checklist. */
@Composable
private fun NoteActionsOverlay(
    note: NoteUi,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onPublish: () -> Unit,
    onPinToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onAddToFolder: () -> Unit,
    onDelete: () -> Unit,
) {
    PopupOverlay(onDismiss = onDismiss) {
        Text(
            text = (if (note.kind == NoteKind.NOTE) "note" else "checklist") +
                if (note.pinned) " · pinned" else "",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PopupAction("copy") { onCopy() }
        PopupAction("share outside of app") { onShare() }
        PopupAction("publish type 1") { onPublish() }
        PopupAction("add to folder →") { onAddToFolder() }
        PopupAction(if (note.pinned) "unpin" else "pin") { onPinToggle() }
        PopupAction("move up") { onMoveUp() }
        PopupAction("move down") { onMoveDown() }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        PopupAction("delete", destructive = true) { onDelete() }
    }
}

/** Long-press popup for a folder. */
@Composable
private fun FolderActionsOverlay(
    folder: NoteFolderUi,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onPinToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    PopupOverlay(onDismiss = onDismiss) {
        Text(
            text = "folder" + if (folder.pinned) " · pinned" else "",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PopupAction("rename") { onRename() }
        PopupAction(if (folder.pinned) "unpin" else "pin") { onPinToggle() }
        PopupAction("move up") { onMoveUp() }
        PopupAction("move down") { onMoveDown() }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
        PopupAction("delete folder (keeps notes)", destructive = true) { onDelete() }
    }
}

/** Folder chooser — assign a note/checklist to a folder or un-file it. */
@Composable
private fun FolderChooserOverlay(
    folders: List<NoteFolderUi>,
    onDismiss: () -> Unit,
    onPick: (String?) -> Unit,
) {
    PopupOverlay(onDismiss = onDismiss) {
        Text(
            text = "move to folder",
            style = BonyType.caption.copy(color = BonyColors.TextMute),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        PopupAction("(no folder)") { onPick(null) }
        folders.forEach { folder ->
            PopupAction(folder.name) { onPick(folder.id) }
        }
    }
}

@Composable
private fun PopupOverlay(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
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
            content = content,
        )
    }
}

@Composable
private fun PopupAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
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

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("note", text))
    }
}

private fun Long.formatNoteDate(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("MMM d · HH:mm"))
