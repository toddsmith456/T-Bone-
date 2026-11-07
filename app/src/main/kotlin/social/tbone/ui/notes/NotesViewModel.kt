package social.tbone.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import social.tbone.notes.ChecklistItem
import social.tbone.notes.NotesCrypto
import social.tbone.notes.NoteWriteCoordinator
import social.tbone.notes.NotesRepository
import social.tbone.notes.NotesStats
import social.tbone.notes.NoteAttachmentUi
import social.tbone.notes.NoteFolderUi
import social.tbone.notes.NoteUi
import javax.inject.Inject

@HiltViewModel
class NotesViewModel @Inject constructor(
    private val repository: NotesRepository,
    private val crypto: NotesCrypto,
    private val noteWrites: NoteWriteCoordinator,
) : ViewModel() {

    val notes: StateFlow<List<NoteUi>> = repository.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<NoteFolderUi>> = repository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stats: StateFlow<NotesStats> = repository.stats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesStats())

    private val _encryptionVerified = MutableStateFlow(false)
    val encryptionVerified: StateFlow<Boolean> = _encryptionVerified.asStateFlow()

    private val _hardwareBacked = MutableStateFlow(false)
    val hardwareBacked: StateFlow<Boolean> = _hardwareBacked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Crypto metadata for the encryption info screen.
    val cipherLabel: String get() = crypto.cipherLabel
    val tagBits: Int get() = crypto.tagBits
    val ivLength: Int get() = crypto.ivLength

    init {
        viewModelScope.launch {
            val (verified, hw) = withContext(Dispatchers.IO) {
                repository.encryptionSelfTest() to repository.isHardwareBacked()
            }
            _encryptionVerified.value = verified
            _hardwareBacked.value = hw
        }
    }

    suspend fun getNote(id: String): NoteUi? = noteWrites.submit { repository.getById(id) }.await()

    fun attachmentsFor(noteId: String): Flow<List<NoteAttachmentUi>> = repository.attachments(noteId)

    /** Saves a note; creates when [id] is null. Returns the id via [onSaved]. */
    fun saveText(
        id: String?,
        title: String,
        text: String,
        folderId: String? = null,
        onError: (Exception) -> Unit = {},
        onSaved: (String) -> Unit = {},
    ) {
        // Enqueue synchronously before the editor can be popped. Only the UI
        // callback is scoped to this ViewModel; persistence is app-scoped.
        val pending = noteWrites.submit { repository.saveText(id, title, text, folderId) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not save (${error.message})"; onError(error) }
        }
    }

    /** Saves a checklist; creates when [id] is null. */
    fun saveChecklist(
        id: String?,
        title: String,
        items: List<ChecklistItem>,
        folderId: String? = null,
        onError: (Exception) -> Unit = {},
        onSaved: (String) -> Unit = {},
    ) {
        val pending = noteWrites.submit { repository.saveChecklist(id, title, items.toList(), folderId) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not save (${error.message})"; onError(error) }
        }
    }

    fun delete(id: String, onError: (Exception) -> Unit = {}, onDeleted: () -> Unit = {}) {
        val pending = noteWrites.submit { repository.delete(id) }
        viewModelScope.launch {
            try { pending.await(); onDeleted() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not delete (${error.message})"; onError(error) }
        }
    }

    // ── pin / move ────────────────────────────────────────────────────────────

    // Submit synchronously so operations cannot cross a wipe barrier while
    // waiting for a ViewModel coroutine to start.
    private fun <T> enqueueNoteOperation(
        action: suspend () -> T,
        onError: (Exception) -> Unit,
        onSuccess: (T) -> Unit = {},
    ) {
        val pending = noteWrites.submit(action)
        viewModelScope.launch {
            try { onSuccess(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { onError(error) }
        }
    }

    /** Pins or unpins (pinned sorts to the very top). */
    fun pinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        enqueueNoteOperation({ repository.setPinned(id, !note.pinned) }, onError = { _error.value = "could not pin (${it.message})" })
    }

    /** Pins or unpins only within the note's current folder. */
    fun folderPinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        enqueueNoteOperation({ repository.setFolderPinned(id, !note.folderPinned) }, onError = { _error.value = "could not pin in folder (${it.message})" })
    }

    /**
     * Moves up one position among un-filed notes. Global pins are untouched.
     */
    fun moveUp(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index - 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    /** Moves down one position among un-filed notes. */
    fun moveDown(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index + 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    /** Reorders only unpinned items inside the folder currently being viewed. */
    fun moveUpInFolder(id: String, folderId: String) {
        val list = notes.value.filter { !it.folderPinned && it.folderId == folderId }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index - 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    fun moveDownInFolder(id: String, folderId: String) {
        val list = notes.value.filter { !it.folderPinned && it.folderId == folderId }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index + 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    // ── folders ───────────────────────────────────────────────────────────────

    fun addFolder(name: String, onSaved: (String) -> Unit = {}) {
        enqueueNoteOperation({ repository.addFolder(name) }, onError = { _error.value = "could not create folder (${it.message})" }, onSuccess = onSaved)
    }

    fun renameFolder(id: String, name: String) = enqueueNoteOperation({ repository.renameFolder(id, name) }, onError = { _error.value = "could not rename folder (${it.message})" })

    fun deleteFolder(id: String) = enqueueNoteOperation({ repository.deleteFolder(id) }, onError = { _error.value = "could not delete folder (${it.message})" })

    fun moveToFolder(noteId: String, folderId: String?) = enqueueNoteOperation({ repository.moveToFolder(noteId, folderId) }, onError = { _error.value = "could not move (${it.message})" })

    fun pinFolderToggle(id: String) {
        val folder = folders.value.find { it.id == id } ?: return
        enqueueNoteOperation({ repository.setFolderRowPinned(id, !folder.pinned) }, onError = { _error.value = "could not pin folder (${it.message})" })
    }

    fun folderMoveUp(id: String) {
        val list = folders.value.filter { !it.pinned }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        enqueueNoteOperation({ repository.swapFolderOrder(list[index].id, list[index - 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    fun folderMoveDown(id: String) {
        val list = folders.value.filter { !it.pinned }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        enqueueNoteOperation({ repository.swapFolderOrder(list[index].id, list[index + 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    // ── attachments ───────────────────────────────────────────────────────────

    /** Encrypts + stores a voice/image attachment for a note. */
    fun addAttachment(
        noteId: String,
        kind: String,
        mime: String,
        bytes: ByteArray,
        onError: (Exception) -> Unit = {},
        onSaved: (String) -> Unit = {},
    ) {
        val pending = noteWrites.submit { repository.addAttachment(noteId, kind, mime, bytes) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not attach (${error.message})"; onError(error) }
        }
    }

    fun deleteAttachment(id: String) {
        val pending = noteWrites.submit { repository.deleteAttachment(id) }
        viewModelScope.launch {
            try { pending.await() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not remove attachment (${error.message})" }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
