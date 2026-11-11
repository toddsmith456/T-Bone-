package social.tbone.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.notes.BookmarkFolderUi
import social.tbone.notes.BookmarkUi
import social.tbone.notes.BookmarkSyncRepository
import social.tbone.notes.BookmarksRepository
import social.tbone.nostr.Event
import social.tbone.profile.ProfileRepository
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    private val syncRepository: BookmarkSyncRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {
    /** Transient status line for online operations (sync, publish, move). */
    private val _onlineMessage = MutableStateFlow<String?>(null)
    val onlineMessage: StateFlow<String?> = _onlineMessage.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    fun clearOnlineMessage() { _onlineMessage.value = null }

    private val screenEpoch = bookmarksRepository.currentWipeEpoch()
    val folders: StateFlow<List<BookmarkFolderUi>> = bookmarksRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles = profileRepository.profiles

    fun observeBookmarks(folderId: String) = bookmarksRepository.observeBookmarks(folderId)

    /**
     * Creates a folder, offline or online.
     *
     * [online] = false keeps the original encrypted, device-only behaviour.
     * [online] = true publishes a NIP-51 kind 30003 set so the folder and its
     * contents follow the account to any client.
     */
    fun createFolder(name: String, online: Boolean, onFinished: (Result<BookmarkFolderUi>) -> Unit) {
        viewModelScope.launch {
            if (!online) {
                onFinished(runCatching { bookmarksRepository.createFolder(name, screenEpoch) })
                return@launch
            }
            val result = syncRepository.createOnlineFolder(name)
            if (result.ok) {
                // The row is already written; the Room-backed folder flow picks it up.
                _onlineMessage.value = result.message
                onFinished(Result.success(
                    folders.value.firstOrNull { it.name == name.trim() && it.isOnline }
                        ?: BookmarkFolderUi(
                            id = "", name = name.trim(), sortOrder = 0.0,
                            createdAt = 0, updatedAt = 0,
                            kind = social.tbone.db.BookmarkFolderKind.ONLINE,
                        ),
                ))
            } else {
                _onlineMessage.value = result.message
                onFinished(Result.failure(IllegalStateException(result.message)))
            }
        }
    }

    /** Pulls every online folder (and its notes) from the relays. */
    fun syncOnline() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            val report = runCatching { syncRepository.sync() }
            _syncing.value = false
            report.onSuccess {
                _onlineMessage.value = it.message
            }.onFailure {
                _onlineMessage.value = "Sync failed: ${it.message ?: "unknown error"}"
            }
        }
    }

    /**
     * Saves [event] into [folder], online or offline.
     *
     * Online saves land on the network immediately (a replaceable set is
     * republished); offline saves stay encrypted on the device.
     */
    fun saveToFolder(event: Event, folder: BookmarkFolderUi, onFinished: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            if (!folder.isOnline) {
                onFinished(
                    runCatching { bookmarksRepository.addBookmark(event, folder.id) }
                        .map { _ -> Unit },
                )
                return@launch
            }
            // Online folders need the note itself cached locally too, otherwise the
            // row would point at an event the device has never seen.
            runCatching { bookmarksRepository.addBookmark(event, folder.id) }
            val result = syncRepository.addToOnlineFolder(folder, event.id)
            _onlineMessage.value = result.message
            onFinished(if (result.ok) Result.success(Unit) else Result.failure(IllegalStateException(result.message)))
        }
    }

    /** Moves [bookmark] from [source] to [destination], across offline/online if needed. */
    fun moveBookmarkEverywhere(
        bookmark: BookmarkUi,
        source: BookmarkFolderUi?,
        destination: BookmarkFolderUi,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        if (source?.id == destination.id) {
            onFinished(Result.success(Unit))
            return
        }
        viewModelScope.launch {
            val sameKind = (source?.kind ?: social.tbone.db.BookmarkFolderKind.OFFLINE) == destination.kind
            if (sameKind && source != null) {
                // Same-side move: the existing local path is enough and keeps row ids.
                onFinished(runCatching { bookmarksRepository.moveBookmark(bookmark.id, destination.id) })
                return@launch
            }
            val result = syncRepository.moveEverywhere(bookmark, source, destination)
            _onlineMessage.value = result.message
            onFinished(if (result.ok) Result.success(Unit) else Result.failure(IllegalStateException(result.message)))
        }
    }

    /**
     * Renames a folder. Online folders are republished first, because a name that
     * only changed on this device would silently revert on the next sync.
     */
    fun renameFolder(folder: BookmarkFolderUi, name: String, onFinished: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            if (!folder.isOnline) {
                onFinished(runCatching { bookmarksRepository.renameFolder(folder.id, name) })
                return@launch
            }
            val result = syncRepository.renameOnlineFolder(folder, name)
            if (result.ok) {
                onFinished(runCatching { bookmarksRepository.renameFolder(folder.id, name) })
            } else {
                _onlineMessage.value = result.message
                onFinished(Result.failure(IllegalStateException(result.message)))
            }
        }
    }

    fun deleteFolder(folderId: String, onFinished: (Result<Boolean>) -> Unit) {
        viewModelScope.launch {
            val folder = folders.value.firstOrNull { it.id == folderId }
            if (folder != null && folder.isOnline) {
                if (folder.isMiscellaneous) {
                    _onlineMessage.value = "The Miscellaneous folder is created from your online bookmarks and cannot be deleted"
                    onFinished(Result.success(false))
                    return@launch
                }
                val result = syncRepository.deleteOnlineFolder(folder)
                _onlineMessage.value = result.message
                onFinished(Result.success(result.ok))
                return@launch
            }
            onFinished(runCatching { bookmarksRepository.deleteFolder(folderId) })
        }
    }

    /**
     * Deletes a bookmark, unpublishing it from the network when the folder is
     * online.
     *
     * F-08: this used to touch the local row only, so deleting from an online
     * folder left the note in the published set and the next sync put it straight
     * back.
     */
    fun deleteBookmark(
        bookmark: BookmarkUi,
        folder: BookmarkFolderUi?,
        onFinished: (Result<Unit>) -> Unit = {},
    ) {
        viewModelScope.launch {
            if (folder?.isOnline != true) {
                onFinished(runCatching { bookmarksRepository.deleteBookmark(bookmark.id) })
                return@launch
            }
            val result = syncRepository.removeBookmarkEverywhere(bookmark, folder)
            if (!result.ok) _onlineMessage.value = result.message
            onFinished(if (result.ok) Result.success(Unit) else Result.failure(IllegalStateException(result.message)))
        }
    }

    fun moveBookmark(bookmarkId: String, targetFolderId: String, onFinished: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            onFinished(runCatching { bookmarksRepository.moveBookmark(bookmarkId, targetFolderId) })
        }
    }

    fun reorder(folderId: String, orderedIds: List<String>) {
        viewModelScope.launch { runCatching { bookmarksRepository.reorder(folderId, orderedIds) } }
    }

    fun quoteFolder(folderId: String, onFinished: (Result<List<BookmarkUi>>) -> Unit) {
        viewModelScope.launch {
            onFinished(runCatching { bookmarksRepository.getBookmarks(folderId) })
        }
    }
}
