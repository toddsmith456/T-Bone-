package social.tbone.ui.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import social.tbone.nostr.Event
import social.tbone.nostr.ProfileContent
import social.tbone.notes.BookmarkFolderUi
import social.tbone.notes.BookmarkUi
import social.tbone.ui.components.UserAvatar
import social.tbone.ui.feed.NoteMediaContent
import social.tbone.ui.feed.parseEventContent
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun BookmarksScreen(
    folderId: String? = null,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenEventSearch: (String) -> Unit,
    onQuoteFolder: (String) -> Unit,
    viewModel: BookmarksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val selectedFolder = folders.firstOrNull { it.id == folderId }
    val bookmarkFlow = remember(folderId) {
        if (folderId == null) flowOf(emptyList()) else viewModel.observeBookmarks(folderId)
    }
    val bookmarks by bookmarkFlow.collectAsState(initial = emptyList())
    var creatingFolder by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<BookmarkFolderUi?>(null) }
    var deletingFolder by remember { mutableStateOf<BookmarkFolderUi?>(null) }
    var quotingFolder by remember { mutableStateOf(false) }

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
                Text(
                    text = selectedFolder?.name ?: "bookmarks",
                    style = BonyType.body.copy(color = BonyColors.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (selectedFolder != null) {
                    Text(
                        text = "${bookmarks.size} saved ${if (bookmarks.size == 1) "note" else "notes"}",
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
            }
            if (selectedFolder == null) {
                IconButton(onClick = { creatingFolder = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = "Create bookmark folder", tint = BonyColors.Accent)
                }
            } else {
                IconButton(
                    onClick = {
                        if (!quotingFolder) {
                            quotingFolder = true
                            viewModel.quoteFolder(selectedFolder.id) { result ->
                                quotingFolder = false
                                result.onSuccess { items ->
                                    if (items.isEmpty()) {
                                        android.widget.Toast.makeText(context, "This folder has no bookmarks to quote", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        onQuoteFolder(selectedFolder.id)
                                    }
                                }.onFailure {
                                    android.widget.Toast.makeText(context, "Could not quote folder", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = !quotingFolder,
                ) {
                    Icon(Icons.Outlined.FormatQuote, contentDescription = "Quote folder", tint = BonyColors.Accent)
                }
            }
            FolderOptionsMenu(
                visible = selectedFolder != null,
                onRename = { selectedFolder?.let { editingFolder = it } },
                onDelete = { selectedFolder?.let { deletingFolder = it } },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        if (selectedFolder == null && folderId != null) {
            Text(
                text = "This bookmark folder no longer exists.",
                style = BonyType.meta.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(18.dp),
            )
        } else if (folderId == null) {
            if (folders.isEmpty()) {
                Text("Preparing your encrypted bookmark vault…", style = BonyType.meta.copy(color = BonyColors.TextMute), modifier = Modifier.padding(18.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { Text("local · encrypted on this device", style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
                    items(folders, key = { it.id }) { folder ->
                        BookmarkFolderRow(
                            folder = folder,
                            onOpen = { onOpenFolder(folder.id) },
                            onRename = { editingFolder = folder },
                            onDelete = { deletingFolder = folder },
                        )
                    }
                }
            }
        } else {
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No notes saved here yet. Use a note’s three-dot menu to bookmark it.",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                    modifier = Modifier.padding(18.dp),
                )
            } else {
                ReorderableBookmarks(
                    folderId = folderId,
                    bookmarks = bookmarks,
                    folders = folders,
                    profiles = profiles,
                    viewModel = viewModel,
                    onOpenEventSearch = onOpenEventSearch,
                )
            }
        }
    }

    if (creatingFolder) {
        FolderNameDialog(
            title = "New bookmark folder",
            initialName = "",
            confirmLabel = "CREATE",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                viewModel.createFolder(name) { result ->
                    result.onSuccess { creatingFolder = false }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "Could not create folder", android.widget.Toast.LENGTH_LONG).show() }
                }
            },
        )
    }
    editingFolder?.let { folder ->
        FolderNameDialog(
            title = "Rename folder",
            initialName = folder.name,
            confirmLabel = "SAVE",
            onDismiss = { editingFolder = null },
            onConfirm = { name ->
                viewModel.renameFolder(folder.id, name) { result ->
                    result.onSuccess { editingFolder = null }
                        .onFailure { android.widget.Toast.makeText(context, it.message ?: "Could not rename folder", android.widget.Toast.LENGTH_LONG).show() }
                }
            },
        )
    }
    deletingFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { deletingFolder = null },
            containerColor = BonyColors.Surface,
            title = { Text("Delete ‘${folder.name}’?", style = BonyType.body.copy(color = BonyColors.Text)) },
            text = { Text("Bookmarks inside this folder will also be deleted. This cannot be undone.", style = BonyType.meta.copy(color = BonyColors.TextDim)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(folder.id) { result ->
                        result.onSuccess { deleted ->
                            deletingFolder = null
                            if (!deleted) android.widget.Toast.makeText(context, "Keep at least one bookmark folder", android.widget.Toast.LENGTH_SHORT).show()
                            else if (folderId == folder.id) onBack()
                        }.onFailure { android.widget.Toast.makeText(context, it.message ?: "Could not delete folder", android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("DELETE", color = BonyColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("CANCEL", color = BonyColors.TextMute) } },
        )
    }
}

@Composable
private fun BookmarkFolderRow(
    folder: BookmarkFolderUi,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember(folder.id) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BonyColors.Surface)
            .border(1.dp, BonyColors.Rule, RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.BookmarkBorder, contentDescription = null, tint = BonyColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.name, style = BonyType.body.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("encrypted local folder", style = BonyType.caption.copy(color = BonyColors.TextMute))
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Folder actions", tint = BonyColors.TextMute)
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
            }
        }
    }
}

@Composable
private fun FolderOptionsMenu(visible: Boolean, onRename: () -> Unit, onDelete: () -> Unit) {
    if (!visible) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Folder actions", tint = BonyColors.TextMute)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename folder") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Delete folder") }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BonyColors.Surface,
        title = { Text(title, style = BonyType.body.copy(color = BonyColors.Text)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(48) },
                singleLine = true,
                label = { Text("folder name") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel, color = BonyColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL", color = BonyColors.TextMute) } },
    )
}

@Composable
private fun ReorderableBookmarks(
    folderId: String,
    bookmarks: List<BookmarkUi>,
    folders: List<BookmarkFolderUi>,
    profiles: Map<String, ProfileContent>,
    viewModel: BookmarksViewModel,
    onOpenEventSearch: (String) -> Unit,
) {
    val positions = remember(folderId) { mutableStateMapOf<String, Float>() }
    var draggedId by remember(folderId) { mutableStateOf<String?>(null) }
    var dragStartCenter by remember(folderId) { mutableFloatStateOf(0f) }
    var dragOffset by remember(folderId) { mutableFloatStateOf(0f) }
    val displayBookmarks = bookmarks
    val currentDragging = displayBookmarks.firstOrNull { it.id == draggedId }
    val targetIndex = if (currentDragging == null || abs(dragOffset) < 18f) -1 else {
        val currentY = dragStartCenter + dragOffset
        val others = displayBookmarks.mapIndexedNotNull { index, item ->
            if (item.id == draggedId) null else positions[item.id]?.let { index to it }
        }
        others.minByOrNull { (_, center) -> abs(center - currentY) }?.first ?: -1
    }
    val currentTargetIndex by rememberUpdatedState(targetIndex)
    val latestDisplayBookmarks by rememberUpdatedState(displayBookmarks)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "drag the handle to reorder · tap a nevent link to open it in Search",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        items(displayBookmarks, key = { it.id }) { bookmark ->
            val profile = profiles[bookmark.event.pubkey]
            val isDragging = bookmark.id == draggedId
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
                    .onGloballyPositioned { coordinates ->
                        positions[bookmark.id] = coordinates.positionInRoot().y + coordinates.size.height / 2f
                    },
            ) {
                BookmarkCard(
                    bookmark = bookmark,
                    profile = profile,
                    folders = folders,
                    dragging = isDragging,
                    onOpenEventSearch = onOpenEventSearch,
                    onDelete = { viewModel.deleteBookmark(bookmark.id) },
                    onMove = { targetFolder -> viewModel.moveBookmark(bookmark.id, targetFolder) },
                    dragHandle = Modifier.pointerInput(bookmark.id, bookmarks) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggedId = bookmark.id
                                dragStartCenter = positions[bookmark.id] ?: 0f
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.y
                            },
                            onDragEnd = {
                                val target = currentTargetIndex
                                val current = latestDisplayBookmarks
                                if (target >= 0) {
                                    val from = current.indexOfFirst { it.id == bookmark.id }
                                    if (from >= 0 && from != target) {
                                        val reordered = current.toMutableList()
                                        val moved = reordered.removeAt(from)
                                        reordered.add(target.coerceIn(0, reordered.size), moved)
                                        viewModel.reorder(folderId, reordered.map { it.id })
                                    }
                                }
                                draggedId = null
                                dragOffset = 0f
                            },
                            onDragCancel = { draggedId = null; dragOffset = 0f },
                        )
                    },
                )
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun BookmarkCard(
    bookmark: BookmarkUi,
    profile: ProfileContent?,
    folders: List<BookmarkFolderUi>,
    dragging: Boolean,
    onOpenEventSearch: (String) -> Unit,
    onDelete: () -> Unit,
    onMove: (String) -> Unit,
    dragHandle: Modifier,
) {
    val context = LocalContext.current
    var menuExpanded by remember(bookmark.id) { mutableStateOf(false) }
    val parsed = remember(bookmark.event.id, bookmark.event.content, bookmark.event.tags) { parseEventContent(bookmark.event) }
    val displayName = profile?.bestName?.takeIf { it.isNotBlank() } ?: "${bookmark.event.pubkey.take(10)}…"
    val timestamp = remember(bookmark.event.createdAt) {
        SimpleDateFormat("MMM d · HH:mm", Locale.getDefault()).format(Date(bookmark.event.createdAt * 1000L))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BonyColors.Surface)
            .border(1.dp, if (dragging) BonyColors.Accent else BonyColors.Rule, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = bookmark.nevent,
            style = BonyType.caption.copy(color = BonyColors.Accent, fontFamily = FontFamily.Monospace),
            modifier = Modifier.clickable { onOpenEventSearch(bookmark.nevent) },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(bookmark.event.pubkey, profile, 36.dp)
            Spacer(Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, style = BonyType.meta.copy(color = BonyColors.Text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(timestamp, style = BonyType.caption.copy(color = BonyColors.TextMute))
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "Bookmark actions", tint = BonyColors.TextMute)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    Text("move to folder", style = BonyType.caption.copy(color = BonyColors.TextMute), modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    folders.filter { it.id != bookmark.folderId }.forEach { folder ->
                        DropdownMenuItem(
                            text = { Text(folder.name) },
                            onClick = {
                                menuExpanded = false
                                onMove(folder.id)
                            },
                        )
                    }
                    DropdownMenuItem(text = { Text("Delete bookmark", color = BonyColors.Danger) }, onClick = { menuExpanded = false; onDelete() })
                }
            }
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = "Drag to reorder bookmark",
                tint = if (dragging) BonyColors.Accent else BonyColors.TextMute,
                modifier = Modifier
                    .size(32.dp)
                    .then(dragHandle),
            )
        }
        if (parsed.text.isNotBlank()) {
            Text(parsed.text, style = BonyType.body.copy(color = BonyColors.Text))
        }
        if (parsed.mediaItems.isNotEmpty()) NoteMediaContent(parsed.mediaItems, Modifier.fillMaxWidth())
    }
}
