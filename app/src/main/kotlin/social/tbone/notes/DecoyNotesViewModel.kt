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
    private val noteWrites: NoteWriteCoordinator,
    val recordingGate: social.tbone.toolbox.VoiceRecordingGate,
) : ViewModel() {
    fun currentWipeEpoch(): Long = noteWrites.currentWipeEpoch()

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
            try {
                val (verified, hw) = noteWrites.submit {
                    repository.encryptionSelfTest() to repository.isHardwareBacked()
                }.await()
                _encryptionVerified.value = verified
                _hardwareBacked.value = hw
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = "encryption unavailable (${error.message})"
            }
        }
    }

    suspend fun getNote(id: String): NoteUi? = noteWrites.submit { repository.getById(id) }.await()

    fun attachmentsFor(noteId: String): Flow<List<NoteAttachmentUi>> = repository.attachments(noteId)

    fun saveText(id: String?, title: String, text: String, folderId: String? = null, epoch: Long = noteWrites.currentWipeEpoch(), onError: (Exception) -> Unit = {}, onSaved: (String) -> Unit = {}) {
        // Enqueue synchronously before the editor can be popped. Only the UI
        // callback is scoped to this ViewModel; persistence is app-scoped.
        val pending = noteWrites.submitAtEpoch(epoch) { repository.saveText(id, title, text, folderId) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not save (${error.message})"; onError(error) }
        }
    }

    fun saveChecklist(id: String?, title: String, items: List<ChecklistItem>, folderId: String? = null, epoch: Long = noteWrites.currentWipeEpoch(), onError: (Exception) -> Unit = {}, onSaved: (String) -> Unit = {}) {
        val pending = noteWrites.submitAtEpoch(epoch) { repository.saveChecklist(id, title, items.toList(), folderId) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not save (${error.message})"; onError(error) }
        }
    }

    fun delete(id: String, epoch: Long = noteWrites.currentWipeEpoch(), onError: (Exception) -> Unit = {}, onDeleted: () -> Unit = {}) {
        val pending = noteWrites.submitAtEpoch(epoch) { repository.delete(id) }
        viewModelScope.launch {
            try { pending.await(); onDeleted() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not delete (${error.message})"; onError(error) }
        }
    }

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

    fun pinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        enqueueNoteOperation({ repository.setPinned(id, !note.pinned) }, onError = { _error.value = "could not pin (${it.message})" })
    }

    fun folderPinToggle(id: String) {
        val note = notes.value.find { it.id == id } ?: return
        enqueueNoteOperation({ repository.setFolderPinned(id, !note.folderPinned) }, onError = { _error.value = "could not pin in folder (${it.message})" })
    }

    fun moveUp(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index <= 0) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index - 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

    fun moveDown(id: String) {
        val list = notes.value.filter { !it.pinned && it.folderId.isNullOrBlank() }
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || index >= list.lastIndex) return
        enqueueNoteOperation({ repository.swapOrder(list[index].id, list[index + 1].id) }, onError = { _error.value = "could not move (${it.message})" })
    }

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

    fun addAttachment(noteId: String, kind: String, mime: String, bytes: ByteArray, epoch: Long = noteWrites.currentWipeEpoch(), onError: (Exception) -> Unit = {}, onSaved: (String) -> Unit = {}) {
        val pending = noteWrites.submitAtEpoch(epoch) { repository.addAttachment(noteId, kind, mime, bytes) }
        viewModelScope.launch {
            try { onSaved(pending.await()) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not attach (${error.message})"; onError(error) }
        }
    }

    /** Atomically store attachment bytes and the text containing their marker. */
    fun addAttachmentAndSaveText(
        noteId: String, attachmentId: String, kind: String, mime: String, bytes: ByteArray,
        title: String, text: String, folderId: String?, epoch: Long,
        onError: (Exception) -> Unit, onSaved: () -> Unit,
    ) {
        val pending = noteWrites.submitAtEpoch(epoch) {
            repository.addAttachmentAndSaveText(noteId, attachmentId, kind, mime, bytes, title, text, folderId)
        }
        viewModelScope.launch {
            try { pending.await(); onSaved() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not attach (${error.message})"; onError(error) }
        }
    }

    fun deleteAttachment(id: String, epoch: Long = noteWrites.currentWipeEpoch()) {
        val pending = noteWrites.submitAtEpoch(epoch) { repository.deleteAttachment(id) }
        viewModelScope.launch {
            try { pending.await() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { _error.value = "could not remove attachment (${error.message})" }
        }
    }

    fun clearError() { _error.value = null }
}
