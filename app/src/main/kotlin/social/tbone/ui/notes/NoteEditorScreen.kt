package social.tbone.ui.notes

import android.Manifest
import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.notes.NoteAttachmentUi
import social.tbone.ui.permissions.rememberPermissionRequester
import social.tbone.ui.theme.BonyColors
import social.tbone.ui.theme.BonyType
import java.io.File

private val MARKER = Regex("""\[\[(voice|image):([0-9a-zA-Z-]+)\]\]""")

/** One storybook block: a paragraph of text, or an inline image/voice box. */
private sealed interface EditorBlock {
    /** Keeps the full TextFieldValue (text + selection + composition) so the
     *  IME's autocorrect and word-replacement behave normally. */
    data class Text(var tfv: TextFieldValue) : EditorBlock {
        val text: String get() = tfv.text
    }

    data class Att(val att: NoteAttachmentUi) : EditorBlock
}

/**
 * Full-screen note editor built as a storybook: paragraphs of text with
 * IMAGE and VOICE boxes rendered INLINE at the spot you insert them (the
 * [[markers]] are hidden — they only exist in storage). The caret is kept
 * centered on screen while you type, and content auto-saves on a debounce and
 * when leaving. Attachments are encrypted with the same notes key.
 */
@Composable
fun NoteEditorScreen(
    id: String,
    folderId: String? = null,
    onBack: () -> Unit,
    onShare: (String) -> Unit,
    onPublish: (String) -> Unit = {},
    viewModel: NotesViewModel = hiltViewModel(),
) {
    var title by remember { mutableStateOf("") }
    var blocks by remember {
        mutableStateOf<List<EditorBlock>>(listOf(EditorBlock.Text(TextFieldValue(""))))
    }
    var savedId by remember { mutableStateOf<String?>(if (id.isBlank()) null else id) }
    var lastSaved by remember { mutableStateOf("") }
    var lastSavedTitle by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var builtFromNote by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var recorderFile by remember { mutableStateOf<File?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    val focusRequesters = remember { mutableStateMapOf<Int, FocusRequester>() }
    val scrollState = rememberScrollState()

    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val perm = rememberPermissionRequester()
    val recorderRef = remember { arrayOfNulls<MediaRecorder>(1) }

    // ── helpers ───────────────────────────────────────────────────────────────
    fun markerOf(att: NoteAttachmentUi) =
        if (att.kind == "image") "[[image:${att.id}]]" else "[[voice:${att.id}]]"

    fun serialize(blocks: List<EditorBlock>): String = blocks.joinToString("") { b ->
        when (b) {
            is EditorBlock.Text -> b.tfv.text
            is EditorBlock.Att -> markerOf(b.att)
        }
    }

    fun plainText(blocks: List<EditorBlock>): String =
        blocks.filterIsInstance<EditorBlock.Text>().joinToString("") { it.tfv.text }

    /** Rebuild blocks from stored text + attachment map (markers → boxes). */
    fun buildBlocks(text: String, atts: Map<String, NoteAttachmentUi>): List<EditorBlock> {
        val out = mutableListOf<EditorBlock>()
        var cursor = 0
        MARKER.findAll(text).forEach { m ->
            if (m.range.first > cursor) {
                out.add(EditorBlock.Text(TextFieldValue(text.substring(cursor, m.range.first))))
            }
            val att = atts[m.groupValues[2]]
            if (att != null) out.add(EditorBlock.Att(att))
            cursor = m.range.last + 1
        }
        if (cursor < text.length) out.add(EditorBlock.Text(TextFieldValue(text.substring(cursor))))
        if (out.isEmpty()) out.add(EditorBlock.Text(TextFieldValue("")))
        return out
    }

    /** Merges adjacent text blocks and drops empty ones (keeps at least one). */
    fun coalesce(blocks: List<EditorBlock>): List<EditorBlock> {
        val out = mutableListOf<EditorBlock>()
        val textBuf = StringBuilder()
        var pendingSel: TextRange? = null
        fun flush() {
            val t = textBuf.toString()
            textBuf.setLength(0)
            if (t.isNotEmpty() || out.isEmpty()) {
                out.add(EditorBlock.Text(TextFieldValue(t, pendingSel ?: TextRange(t.length))))
            }
            pendingSel = null
        }
        blocks.forEach { b ->
            when (b) {
                is EditorBlock.Text -> {
                    textBuf.append(b.tfv.text)
                    pendingSel = b.tfv.selection
                }
                is EditorBlock.Att -> {
                    flush()
                    out.add(b)
                }
            }
        }
        flush()
        if (out.isEmpty()) out.add(EditorBlock.Text(TextFieldValue("")))
        // Always end on a TEXT block: if the last block is an attachment there
        // must be an empty line after it so you can tap below it and keep
        // typing (same as every messenger).
        if (out.lastOrNull() is EditorBlock.Att) {
            out.add(EditorBlock.Text(TextFieldValue("", TextRange(0))))
        }
        return out
    }

    fun performSave(onDone: (String) -> Unit = {}) {
        val text = serialize(blocks)
        if (text.isBlank() && title.isBlank()) return
        val eid = savedId ?: java.util.UUID.randomUUID().toString()
        savedId = eid
        viewModel.saveText(eid, title, text, folderId) { newId ->
            savedId = newId
            lastSaved = serialize(blocks)
            lastSavedTitle = title
            onDone(newId)
        }
    }

    // ── load ──────────────────────────────────────────────────────────────────
    // rawText keeps the stored text (with [[markers]]) until attachments have
    // been resolved into boxes; after the one-time build, `blocks` is the
    // source of truth so editing/inserting never gets clobbered.
    var rawText by remember { mutableStateOf<String?>(null) }
    var builtOnce by remember { mutableStateOf(false) }
    val attachmentsFlow = savedId?.let { viewModel.attachmentsFor(it) }
    var attachmentsMap by remember { mutableStateOf<Map<String, NoteAttachmentUi>>(emptyMap()) }
    attachmentsFlow?.let { flow ->
        LaunchedEffect(flow) {
            flow.collect { list -> attachmentsMap = list.associateBy { it.id } }
        }
    }

    LaunchedEffect(id) {
        if (id.isNotBlank()) {
            viewModel.getNote(id)?.let { note ->
                title = note.title
                rawText = note.text
                lastSaved = note.text
                lastSavedTitle = note.title
                savedId = note.id
            }
        }
        loaded = true
        if (id.isBlank()) {
            delay(120)
            focusRequesters[0]?.requestFocus()
        }
    }
    // One-time rebuild of the storybook blocks once the note text AND its
    // attachments are both available.
    LaunchedEffect(attachmentsMap, rawText, savedId, loaded) {
        val rt = rawText
        if (loaded && !builtOnce && rt != null && savedId != null) {
            blocks = buildBlocks(rt, attachmentsMap)
            builtOnce = true
        }
    }

    // ── autosave ──────────────────────────────────────────────────────────────
    LaunchedEffect(blocks, title, loaded) {
        if (loaded) {
            delay(1_200)
            if (serialize(blocks) != lastSaved || title != lastSavedTitle) performSave()
        }
    }
    val saveOnExit by rememberUpdatedState({ performSave() })
    DisposableEffect(Unit) { onDispose { saveOnExit() } }

    fun back() {
        saveOnExit()
        onBack()
    }

    // ── insert attachment at the focused text block's cursor ──────────────────
    fun insertAttachment(att: NoteAttachmentUi) {
        val idx = focusedIndex.coerceIn(0, blocks.lastIndex)
        val block = blocks[idx]
        if (block is EditorBlock.Text) {
            val sel = block.tfv.selection
            val text = block.tfv.text
            val start = sel.start.coerceIn(0, text.length)
            val end = sel.end.coerceIn(0, text.length)
            val before = EditorBlock.Text(TextFieldValue(text.substring(0, start), TextRange(start)))
            val after = EditorBlock.Text(TextFieldValue(text.substring(end), TextRange(0)))
            val newBlocks = blocks.toMutableList().apply {
                this[idx] = before
                add(idx + 1, EditorBlock.Att(att))
                add(idx + 2, after)
            }
            blocks = coalesce(newBlocks)
            focusedIndex = idx + 2
            focusRequesters[idx + 2]?.requestFocus()
        } else {
            blocks = coalesce(
                blocks.toMutableList().apply { add(this.size, EditorBlock.Att(att)) },
            )
            // focus the trailing text block the coalesce() call guarantees.
            focusedIndex = blocks.lastIndex
            focusRequesters[blocks.lastIndex]?.requestFocus()
        }
    }

    /** Focuses the next TEXT block after [afterIndex] (used by the inline
     *  attachment cards so tapping below an image/voice continues typing). */
    fun focusNextTextBlock(afterIndex: Int) {
        val next = blocks.indices.firstOrNull { it > afterIndex && blocks[it] is EditorBlock.Text }
            ?: blocks.lastIndex
        focusedIndex = next
        focusRequesters[next]?.requestFocus()
    }

    fun attach(kind: String, mime: String, bytes: ByteArray) {
        fun saveInto(noteId: String) {
            viewModel.addAttachment(noteId, kind, mime, bytes) { attId ->
                val placeholder = NoteAttachmentUi(attId, noteId, kind, mime, bytes, bytes.size.toLong(), 0L)
                insertAttachment(placeholder)
            }
        }
        val nid = savedId
        if (nid != null) saveInto(nid)
        else performSave { newId -> saveInto(newId) }
    }

    fun beginRecording() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(context.cacheDir, "note_voice_${System.currentTimeMillis()}.m4a")
                    val r = MediaRecorder(context).apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(f.absolutePath)
                        prepare()
                        start()
                    }
                    recorderRef[0] = r
                    f
                }.getOrNull()
            }
            if (file != null) {
                recorderFile = file
                recording = true
            }
        }
    }

    fun startRecording() {
        perm.requestOrRun(
            permissions = listOf(Manifest.permission.RECORD_AUDIO),
            action = { beginRecording() },
        )
    }

    fun stopRecording() {
        recording = false
        val file = recorderFile ?: return
        recorderFile = null
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    recorderRef[0]?.stop()
                    recorderRef[0]?.release()
                    recorderRef[0] = null
                    file.readBytes()
                }.getOrNull()
            }
            if (bytes != null && bytes.isNotEmpty()) attach("voice", "audio/mp4", bytes)
        }
    }

    fun removeAttachment(att: NoteAttachmentUi) {
        viewModel.deleteAttachment(att.id)
        // Remove the card AND merge the surrounding text blocks so no empty
        // gap is left where the image/voice used to be.
        blocks = coalesce(blocks.filterNot { it is EditorBlock.Att && it.att.id == att.id })
    }

    // Photo picker.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    val mime = runCatching {
                        context.contentResolver.getType(uri)
                    }.getOrNull() ?: "image/*"
                    attach("image", mime, bytes)
                }
            }
        }
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
            Text(
                text = "←",
                style = BonyType.body.copy(color = BonyColors.TextMute),
                modifier = Modifier.clickable { back() },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (id.isBlank()) "new note" else "note",
                style = BonyType.body.copy(color = BonyColors.Text),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "share",
                style = BonyType.tag.copy(color = BonyColors.Accent),
                modifier = Modifier
                    .border(1.dp, BonyColors.AccentDim)
                    .clickable { onShare(plainText(blocks)) }
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

        // ── Title ─────────────────────────────────────────────────────────────
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            placeholder = { Text("title", style = BonyType.meta.copy(color = BonyColors.TextMute)) },
            singleLine = true,
            textStyle = BonyType.body.copy(color = BonyColors.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        // ── Storybook body: text blocks + inline image/voice boxes ────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
                blocks.forEachIndexed { index, block ->
                    when (block) {
                        is EditorBlock.Text -> {
                            val isFocused = focusedIndex == index
                            val requester = focusRequesters.getOrPut(index) { FocusRequester() }
                            // Pass the SAME TextFieldValue instance back (text,
                            // selection AND composition), so the IME never loses
                            // its composition — autocorrect and select-to-replace
                            // work exactly like a normal text field.
                            BasicTextField(
                                value = block.tfv,
                                onValueChange = { tf ->
                                    blocks = blocks.mapIndexed { i, b ->
                                        if (i == index) EditorBlock.Text(tf) else b
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(requester)
                                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
                                textStyle = TextStyle(
                                    color = BonyColors.Text,
                                    fontFamily = BonyType.body.fontFamily,
                                    fontSize = BonyType.body.fontSize,
                                    lineHeight = BonyType.body.lineHeight,
                                ),
                                cursorBrush = SolidColor(BonyColors.Accent),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    autoCorrectEnabled = true,
                                    capitalization = KeyboardCapitalization.Sentences,
                                ),
                                decorationBox = { inner ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 6.dp),
                                    ) {
                                        if (block.tfv.text.isEmpty() && blocks.size == 1 && title.isEmpty()) {
                                            Text(
                                                text = "write something…",
                                                style = BonyType.meta.copy(color = BonyColors.TextMute),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        is EditorBlock.Att -> {
                            // Tapping the card area (not the play/delete buttons)
                            // moves the caret to the line right below it so you
                            // can keep typing without reaching for the keyboard.
                            Box(modifier = Modifier.clickable { focusNextTextBlock(index) }) {
                                AttachmentCard(
                                    att = block.att,
                                    playing = playingId == block.att.id,
                                    onPlay = {
                                        if (playingId == block.att.id) {
                                            stopPlayback(); playingId = null
                                        } else {
                                            stopPlayback(); playingId = block.att.id
                                            playAttachment(context, block.att) { playingId = null }
                                        }
                                    },
                                    onDelete = { removeAttachment(block.att) },
                                )
                            }
                        }
                    }
                }
                // Bottom buffer so the last block never hugs the gesture bar.
                Spacer(Modifier.height(72.dp))
            }
        // ── Attachment toolbar ────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BonyColors.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarChip(
                text = if (recording) "■ recording… tap to stop" else "🎤 voice",
                active = recording,
                onClick = { if (recording) stopRecording() else startRecording() },
            )
            ToolbarChip("image", active = false, onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, icon = Icons.Default.Image)
            Text(
                text = "permission needed: " + perm.lastDenial.orEmpty(),
                style = BonyType.caption.copy(color = BonyColors.Danger),
                modifier = Modifier.weight(1f),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorderRef[0]?.release() }
            stopPlayback()
        }
    }
}

@Composable
private fun ToolbarChip(text: String, active: Boolean, onClick: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .border(1.dp, if (active) BonyColors.Danger else BonyColors.AccentDim)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (active) BonyColors.Danger else BonyColors.Accent)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = text,
            style = BonyType.tag.copy(
                color = if (active) BonyColors.Danger else BonyColors.Accent,
            ),
        )
    }
}

/** An inline storybook card for an attachment. Images render as the image
 *  itself (no box), filling the width like the card used to, with a small
 *  delete button overlaid in the corner. Voices keep a compact play card. */
@Composable
private fun AttachmentCard(
    att: NoteAttachmentUi,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    if (att.kind == "image") {
        val bmp = remember(att.id) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeByteArray(att.data, 0, att.data.size, opts)
            }.getOrNull()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .height(160.dp)
                .background(Color.Black),
        ) {
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "image attachment",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(imageVector = Icons.Default.Image, contentDescription = "image attachment", modifier = Modifier.size(32.dp), tint = BonyColors.TextMute)
                }
            }
            // Delete overlay — always visible, small, top-right.
            Text(
                text = "✕",
                style = BonyType.body.copy(color = BonyColors.Danger),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(BonyColors.Bg.copy(alpha = 0.85f))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    } else {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .border(1.dp, BonyColors.RuleStrong)
            .background(BonyColors.SurfaceAlt),
    ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (playing) "⏸" else "▶",
                    style = BonyType.body.copy(color = BonyColors.Accent),
                    modifier = Modifier
                        .border(1.dp, BonyColors.AccentDim)
                        .clickable(onClick = onPlay)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "voice note",
                        style = BonyType.meta.copy(color = BonyColors.Text),
                    )
                    Text(
                        text = formatSize(att.size),
                        style = BonyType.caption.copy(color = BonyColors.TextMute),
                    )
                }
                Text(
                    text = "✕",
                    style = BonyType.body.copy(color = BonyColors.Danger),
                    modifier = Modifier.clickable(onClick = onDelete).padding(4.dp),
                )
            }
        }
    }
}

private var player: MediaPlayer? = null

private fun playAttachment(context: Context, att: NoteAttachmentUi, onDone: () -> Unit) {
    runCatching {
        val file = File(context.cacheDir, "play_${att.id}.m4a")
        file.writeBytes(att.data)
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { stopPlayback(); onDone() }
            prepare()
            start()
        }
    }
}

private fun stopPlayback() {
    runCatching {
        player?.stop()
        player?.release()
        player = null
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
