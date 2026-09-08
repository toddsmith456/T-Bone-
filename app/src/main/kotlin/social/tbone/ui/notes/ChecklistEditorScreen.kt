package social.tbone.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import social.tbone.notes.ChecklistCodec
import social.tbone.notes.ChecklistData
import social.tbone.notes.ChecklistItem
import social.tbone.notes.NoteKind
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import kotlinx.coroutines.delay

/**
 * Full-screen checklist editor. Encrypted with the exact same AES-256-GCM key
 * as notes (the checklist is serialized to JSON, then encrypted). The add-item
 * field is pinned above the keyboard via imePadding, so the typing space never
 * disappears under it. Auto-saves on a debounce and again when leaving. Item
 * text can contain web links — tinted, tap to open, long-press to copy.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChecklistEditorScreen(
    id: String,
    folderId: String? = null,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onPublish: (String) -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {

    var title by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ChecklistItem>>(emptyList()) }
    var savedId by remember { mutableStateOf<String?>(if (id.isBlank()) null else id) }
    var lastSavedJson by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }

    // Load the existing checklist (if editing).
    LaunchedEffect(id) {
        if (id.isNotBlank()) {
            viewModel.getNote(id)?.let { note ->
                if (note.kind == NoteKind.CHECKLIST) {
                    val data = note.checklist()
                    title = data?.title ?: ""
                    items = data?.items ?: emptyList()
                    lastSavedJson = ChecklistCodec.encode(
                        ChecklistData(title, items)
                    )
                    savedId = note.id
                }
            }
        }
        loaded = true
    }

    fun currentJson(): String = ChecklistCodec.encode(
        ChecklistData(title, items.filter { it.text.isNotBlank() })
    )

    fun performSave() {
        val json = currentJson()
        if (json == lastSavedJson) return
        viewModel.saveChecklist(savedId, title, items, folderId) { newId ->
            savedId = newId
            lastSavedJson = json
        }
    }

    LaunchedEffect(title, items, loaded) {
        if (loaded) {
            delay(1_200)
            performSave()
        }
    }

    val saveOnExit by rememberUpdatedState({ performSave() })
    DisposableEffect(Unit) {
        onDispose { saveOnExit() }
    }

    fun back() {
        saveOnExit()
        onBack()
    }

    fun addItem() {
        val t = draft.trim()
        if (t.isEmpty()) return
        items = items + ChecklistItem(text = t)
        draft = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg)
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        // ── Top bar: back · title · share / delete ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { back() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (id.isBlank()) "new checklist" else "checklist",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "share",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .border(1.dp, BonyColors.AccentDim)
                    .clickable { onShare(ChecklistData(title, items).toDisplayText()) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "delete",
                style = BonyType.tag.copy(color = BonyColors.Danger),
                modifier = Modifier
                    .border(1.dp, BonyColors.Danger)
                    .clickable {
                        savedId?.let { viewModel.delete(it) }
                        onBack()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))

        // ── Title + progress ──────────────────────────────────────────────────
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = { Text("checklist title", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            singleLine = true,
            textStyle = BonyType.body.copy(color = BonyColors.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val done = items.count { it.checked }
            Text(
                text = if (items.isEmpty()) "no items yet"
                    else "$done/${items.size} done",
                style = BonyType.caption.copy(color = BonyColors.TextMute),
                modifier = Modifier.weight(1f),
            )
        }

        // ── Items ─────────────────────────────────────────────────────────────
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "add your first item below",
                    style = BonyType.meta.copy(color = BonyColors.TextMute),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(items, key = { index, item -> item.text to index }) { index, item ->
                    ChecklistItemRow(
                        item = item,
                        onToggle = {
                            items = items.mapIndexed { i, it ->
                                if (i == index) it.copy(checked = !it.checked) else it
                            }
                        },
                        onDelete = {
                            items = items.filterIndexed { i, _ -> i != index }
                        },
                        onEdit = {
                            editingIndex = index
                            editText = item.text
                        },
                    )
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BonyColors.Rule))
                }
                // Bottom buffer so the last item never hugs the add bar.
                item { Spacer(Modifier.height(48.dp)) }
            }
        }

        // ── Add-item field: pinned above the keyboard ─────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("add an item…", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
                singleLine = true,
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addItem() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .border(1.dp, if (draft.isNotBlank()) BonyColors.Accent else BonyColors.Rule)
                    .clickable(enabled = draft.isNotBlank()) { addItem() }
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "ADD",
                    style = BonyType.tag.copy(
                        color = if (draft.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                )
            }
        }
    }

    // Item edit overlay.
    editingIndex?.let { index ->
        ItemEditOverlay(
            initial = editText,
            onDismiss = { editingIndex = null },
            onSave = { newText ->
                val t = newText.trim()
                if (t.isNotEmpty()) {
                    items = items.mapIndexed { i, it ->
                        if (i == index) it.copy(text = t) else it
                    }
                }
                editingIndex = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChecklistItemRow(
    item: ChecklistItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onEdit)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Custom checkbox matching the app's text/border style.
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(1.dp, if (item.checked) BonyColors.Accent else BonyColors.RuleStrong)
                .background(if (item.checked) BonyColors.AccentBg else BonyColors.Bg)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) {
                Text(
                    text = "✓",
                    style = BonyType.tag.copy(color = BonyColors.Accent),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Link-aware text: tap a link to open it, long-press to copy.
        LinkSegmentedText(
            text = item.text,
            style = BonyType.body.copy(
                color = if (item.checked) BonyColors.TextMute else BonyColors.Text,
            ),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "✎",
            style = BonyType.metaDim.copy(color = BonyColors.TextMute),
            modifier = Modifier
                .padding(6.dp)
                .clickable(onClick = onEdit),
        )
        Text(
            text = "×",
            style = BonyType.body.copy(color = BonyColors.Danger),
            modifier = Modifier
                .padding(6.dp)
                .clickable(onClick = onDelete),
        )
    }
}

/** Centered overlay to edit a single checklist item. */
@Composable
private fun ItemEditOverlay(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BonyColors.Bg.copy(alpha = 0.75f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .border(1.dp, BonyColors.RuleStrong)
                .background(BonyColors.Surface)
                .clickable(enabled = false) {}
                .padding(16.dp),
        ) {
            Text(
                text = "edit item",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = "cancel",
                    style = BonyType.tag.copy(color = BonyColors.TextMute),
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "save",
                    style = BonyType.tag.copy(
                        color = if (text.isNotBlank()) BonyColors.Accent else BonyColors.TextMute,
                    ),
                    modifier = Modifier
                        .clickable(enabled = text.isNotBlank()) { onSave(text) }
                        .padding(8.dp),
                )
            }
        }
    }
}
