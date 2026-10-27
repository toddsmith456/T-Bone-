package social.tbone.notes

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
import javax.inject.Inject

@HiltViewModel
class DecoyNotesViewModel @Inject constructor(
    private val repository: DecoyNotesRepository,
    private val crypto: NotesCrypto,
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

    suspend fun getNote(id: String): NoteUi? = withContext(Dispatchers.IO) { repository.getById(id) }

    fun attachmentsFor(noteId: String): Flow<List<NoteAttachmentUi>> = repository.attachments(noteId)

    fun saveText(id: String?, title: String, text: String, folderId: String? = null, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.saveText(id, title, text, folderId) }
                .onSuccess { onSaved(it) }
                .onFailure { _error.value = "could not save (${it.message})" }
        }
    }

    fun saveChecklist(id: String?, title: String, items: List<ChecklistItem>, folderId: String? = null, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.saveChecklist(id, title, items, folderId) }
                .onSuccess { onSaved(it) }
                .onFailure { _error.value = "could not save (${it.message})" }
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        runCatching { repository.delete(id) }.onFailure { _error.value = "could not delete (${it.message})" }
    }

    fun pinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { repository.setPinned(id, !note.pinned) }
                .onFailure { _error.value = "could not pin (${it.message})" }
        }
    }

    fun folderPinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { repository.setFolderPinned(id, !note.folderPinned) }
                .onFailure { _error.value = "could not pin in folder (${it.message})" }
        }
    }

    fun moveUp(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        viewModelScope.launch {
            runCatching { repository.swapOrder(list[index].id, list[index - 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun moveDown(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        viewModelScope.launch {
            runCatching { repository.swapOrder(list[index].id, list[index + 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun moveUpInFolder(id: String, folderId: String) {
        val list = notes.value.filter { !it.folderPinned && it.folderId == folderId }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        viewModelScope.launch {
            runCatching { repository.swapOrder(list[index].id, list[index - 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun moveDownInFolder(id: String, folderId: String) {
        val list = notes.value.filter { !it.folderPinned && it.folderId == folderId }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        viewModelScope.launch {
            runCatching { repository.swapOrder(list[index].id, list[index + 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun addFolder(name: String, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addFolder(name) }
                .onSuccess { onSaved(it) }
                .onFailure { _error.value = "could not create folder (${it.message})" }
        }
    }

    fun renameFolder(id: String, name: String) = viewModelScope.launch {
        runCatching { repository.renameFolder(id, name) }
            .onFailure { _error.value = "could not rename folder (${it.message})" }
    }

    fun deleteFolder(id: String) = viewModelScope.launch {
        runCatching { repository.deleteFolder(id) }
            .onFailure { _error.value = "could not delete folder (${it.message})" }
    }

    fun moveToFolder(noteId: String, folderId: String?) = viewModelScope.launch {
        runCatching { repository.moveToFolder(noteId, folderId) }
            .onFailure { _error.value = "could not move (${it.message})" }
    }

    fun pinFolderToggle(id: String) {
        val folder = folders.value.find { it.id == id } ?: return
        viewModelScope.launch {
            runCatching { repository.setFolderRowPinned(id, !folder.pinned) }
                .onFailure { _error.value = "could not pin folder (${it.message})" }
        }
    }

    fun folderMoveUp(id: String) {
        val list = folders.value.filter { !it.pinned }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        viewModelScope.launch {
            runCatching { repository.swapFolderOrder(list[index].id, list[index - 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun folderMoveDown(id: String) {
        val list = folders.value.filter { !it.pinned }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        viewModelScope.launch {
            runCatching { repository.swapFolderOrder(list[index].id, list[index + 1].id) }
                .onFailure { _error.value = "could not move (${it.message})" }
        }
    }

    fun addAttachment(noteId: String, kind: String, mime: String, bytes: ByteArray, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repository.addAttachment(noteId, kind, mime, bytes) }
                .onSuccess { onSaved(it) }
                .onFailure { _error.value = "could not attach (${it.message})" }
        }
    }

    fun deleteAttachment(id: String) = viewModelScope.launch {
        runCatching { repository.deleteAttachment(id) }
            .onFailure { _error.value = "could not remove attachment (${it.message})" }
    }

    fun clearError() { _error.value = null }
}
