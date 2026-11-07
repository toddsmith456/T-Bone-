package social.tbone.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import social.tbone.notes.BookmarkFolderUi
import social.tbone.notes.BookmarkUi
import social.tbone.notes.BookmarksRepository
import social.tbone.profile.ProfileRepository
import javax.inject.Inject

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarksRepository: BookmarksRepository,
    profileRepository: ProfileRepository,
) : ViewModel() {
    private val screenEpoch = bookmarksRepository.currentWipeEpoch()
    val folders: StateFlow<List<BookmarkFolderUi>> = bookmarksRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profiles = profileRepository.profiles

    fun observeBookmarks(folderId: String) = bookmarksRepository.observeBookmarks(folderId)

    fun createFolder(name: String, onFinished: (Result<BookmarkFolderUi>) -> Unit) {
        viewModelScope.launch { onFinished(runCatching { bookmarksRepository.createFolder(name, screenEpoch) }) }
    }

    fun renameFolder(folderId: String, name: String, onFinished: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            onFinished(runCatching { bookmarksRepository.renameFolder(folderId, name) })
        }
    }

    fun deleteFolder(folderId: String, onFinished: (Result<Boolean>) -> Unit) {
        viewModelScope.launch { onFinished(runCatching { bookmarksRepository.deleteFolder(folderId) }) }
    }

    fun deleteBookmark(bookmarkId: String, onFinished: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch { onFinished(runCatching { bookmarksRepository.deleteBookmark(bookmarkId) }) }
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
