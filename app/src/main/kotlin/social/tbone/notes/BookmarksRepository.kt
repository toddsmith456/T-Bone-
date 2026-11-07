package social.tbone.notes

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import social.tbone.db.BookmarkDao
import social.tbone.db.BookmarkEntity
import social.tbone.db.BookmarkFolderEntity
import social.tbone.nostr.Event
import social.tbone.nostr.Nip19
import social.tbone.nostr.NostrJson
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Decrypted folder model used by the bookmark UI. */
data class BookmarkFolderUi(
    val id: String,
    val name: String,
    val sortOrder: Double,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Decrypted saved event. It is kept in memory only while the UI is displaying it. */
data class BookmarkUi(
    val id: String,
    val folderId: String,
    val event: Event,
    val sortOrder: Double,
    val createdAt: Long,
) {
    val nevent: String
        get() = Nip19.hexToNevent(event.id, event.pubkey, event.kind)
}

enum class BookmarkSaveResult { SAVED, ALREADY_SAVED }

/**
 * Device-local bookmark vault. Both folder names and serialized Nostr events
 * are encrypted with the same AES-256-GCM Android Keystore key used by Notes.
 * Only ciphertext and per-record IVs are persisted in Room.
 */
@Singleton
class BookmarksRepository @Inject constructor(
    private val dao: BookmarkDao,
    private val crypto: NotesCrypto,
    private val noteWrites: NoteWriteCoordinator,
) {
    private val defaultFolderMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    val folders: Flow<List<BookmarkFolderUi>> = noteWrites.available.flatMapLatest { available ->
        if (!available) flowOf(emptyList()) else flow {
            try {
                coordinated { ensureDefaultFolder() }
            } catch (error: java.io.IOException) {
                if (noteWrites.available.value) throw error
                emit(emptyList())
                return@flow
            }
            emitAll(dao.observeFolders().map { rows -> rows.mapNotNull(::decryptFolder) })
        }
    }

    suspend fun getFolders(): List<BookmarkFolderUi> = coordinated { getFoldersInternal() }

    private suspend fun getFoldersInternal(): List<BookmarkFolderUi> {
        ensureDefaultFolder()
        return dao.getFolders().mapNotNull(::decryptFolder)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBookmarks(folderId: String): Flow<List<BookmarkUi>> =
        noteWrites.available.flatMapLatest { available ->
            if (!available) flowOf(emptyList())
            else dao.observeBookmarks(folderId).map { rows -> rows.mapNotNull(::decryptBookmark) }
        }

    suspend fun getBookmarks(folderId: String): List<BookmarkUi> = coordinated {
        dao.getBookmarks(folderId).mapNotNull(::decryptBookmark)
    }

    fun currentWipeEpoch(): Long = noteWrites.currentWipeEpoch()

    suspend fun createFolder(name: String, epoch: Long = noteWrites.currentWipeEpoch()): BookmarkFolderUi =
        noteWrites.submitAtEpoch(epoch) { createFolderInternal(name) }.await()

    private suspend fun createFolderInternal(name: String): BookmarkFolderUi {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a folder name." }
        require(cleanName.length <= MAX_FOLDER_NAME_LENGTH) { "Folder names can be at most $MAX_FOLDER_NAME_LENGTH characters." }
        ensureDefaultFolder()
        val existingNames = getFoldersInternal().map { it.name.trim().lowercase() }.toSet()
        require(cleanName.lowercase() !in existingNames) { "A folder with that name already exists." }
        val now = System.currentTimeMillis()
        val (ciphertext, iv) = crypto.encrypt(cleanName)
        val folder = BookmarkFolderEntity(
            id = UUID.randomUUID().toString(),
            encryptedName = ciphertext,
            iv = iv,
            sortOrder = (dao.getFolders().maxOfOrNull { it.sortOrder } ?: -1.0) + 1.0,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertFolder(folder)
        return decryptFolder(folder) ?: error("Could not decrypt the new folder.")
    }

    suspend fun renameFolder(folderId: String, name: String): Unit =
        coordinated { renameFolderInternal(folderId, name) }

    private suspend fun renameFolderInternal(folderId: String, name: String) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Enter a folder name." }
        require(cleanName.length <= MAX_FOLDER_NAME_LENGTH) { "Folder names can be at most $MAX_FOLDER_NAME_LENGTH characters." }
        val folder = dao.getFolder(folderId) ?: error("That folder no longer exists.")
        val duplicate = getFoldersInternal().any { it.id != folderId && it.name.trim().equals(cleanName, ignoreCase = true) }
        require(!duplicate) { "A folder with that name already exists." }
        val (ciphertext, iv) = crypto.encrypt(cleanName)
        dao.upsertFolder(folder.copy(encryptedName = ciphertext, iv = iv, updatedAt = System.currentTimeMillis()))
    }

    /** Returns false when deleting this folder would remove the last folder. */
    suspend fun deleteFolder(folderId: String): Boolean =
        coordinated { deleteFolderInternal(folderId) }

    private suspend fun deleteFolderInternal(folderId: String): Boolean {
        ensureDefaultFolder()
        if (dao.folderCount() <= 1) return false
        dao.deleteFolder(folderId)
        return true
    }

    suspend fun addBookmark(event: Event, folderId: String, epoch: Long = noteWrites.currentWipeEpoch()): BookmarkSaveResult =
        noteWrites.submitAtEpoch(epoch) { addBookmarkInternal(event, folderId) }.await()

    private suspend fun addBookmarkInternal(event: Event, folderId: String): BookmarkSaveResult {
        require(event.verify()) { "This event failed signature verification and was not bookmarked." }
        ensureDefaultFolder()
        val folder = dao.getFolder(folderId) ?: error("That folder no longer exists.")
        val existing = dao.getBookmarks(folder.id).mapNotNull(::decryptBookmark)
            .firstOrNull { it.event.id == event.id }
        if (existing != null) return BookmarkSaveResult.ALREADY_SAVED

        val now = System.currentTimeMillis()
        val payload = NostrJson.encodeToString(event)
        val (ciphertext, iv) = crypto.encrypt(payload)
        dao.upsertBookmark(
            BookmarkEntity(
                id = UUID.randomUUID().toString(),
                folderId = folder.id,
                ciphertext = ciphertext,
                iv = iv,
                sortOrder = dao.maxBookmarkOrder(folder.id) + 1.0,
                createdAt = now,
            ),
        )
        return BookmarkSaveResult.SAVED
    }

    suspend fun deleteBookmark(bookmarkId: String) = coordinated { dao.deleteBookmark(bookmarkId) }

    /** Moving between folders reuses encrypted bytes; it never writes plaintext to disk. */
    suspend fun moveBookmark(bookmarkId: String, targetFolderId: String): Unit =
        coordinated { moveBookmarkInternal(bookmarkId, targetFolderId) }

    private suspend fun moveBookmarkInternal(bookmarkId: String, targetFolderId: String) {
        val source = dao.getAllBookmarks().firstOrNull { it.id == bookmarkId }
            ?: error("That bookmark no longer exists.")
        if (source.folderId == targetFolderId) return
        require(dao.getFolder(targetFolderId) != null) { "That folder no longer exists." }
        val eventId = decryptBookmark(source)?.event?.id
        val duplicate = dao.getBookmarks(targetFolderId).mapNotNull(::decryptBookmark)
            .any { it.event.id == eventId }
        if (duplicate) {
            // Moving to a folder that already contains the event is a merge, not a duplicate.
            dao.deleteBookmark(bookmarkId)
            return
        }
        dao.upsertBookmark(
            source.copy(
                folderId = targetFolderId,
                sortOrder = dao.maxBookmarkOrder(targetFolderId) + 1.0,
            ),
        )
    }

    suspend fun reorder(folderId: String, orderedBookmarkIds: List<String>): Unit =
        coordinated { reorderInternal(folderId, orderedBookmarkIds) }

    private suspend fun reorderInternal(folderId: String, orderedBookmarkIds: List<String>) {
        val currentIds = dao.getBookmarks(folderId).map { it.id }.toSet()
        val ordered = orderedBookmarkIds.filter { it in currentIds }.distinct() +
            currentIds.filterNot { it in orderedBookmarkIds.toSet() }
        dao.reorderBookmarks(folderId, ordered)
    }

    /** Local search for a saved event; decryption happens only in memory. */
    suspend fun findEvent(eventId: String): Event? = coordinated {
        dao.getAllBookmarks().asSequence()
            .mapNotNull(::decryptBookmark)
            .firstOrNull { it.event.id.equals(eventId, ignoreCase = true) }
            ?.event
    }

    private suspend fun <T> coordinated(action: suspend () -> T): T = noteWrites.submit(action).await()

    private suspend fun ensureDefaultFolder() {
        defaultFolderMutex.withLock {
            if (dao.folderCount() > 0) return
            val now = System.currentTimeMillis()
            val (ciphertext, iv) = crypto.encrypt(DEFAULT_FOLDER_NAME)
            dao.upsertFolder(
                BookmarkFolderEntity(
                    id = DEFAULT_FOLDER_ID,
                    encryptedName = ciphertext,
                    iv = iv,
                    sortOrder = 0.0,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun decryptFolder(row: BookmarkFolderEntity): BookmarkFolderUi? = runCatching {
        BookmarkFolderUi(
            id = row.id,
            name = crypto.decrypt(row.encryptedName, row.iv),
            sortOrder = row.sortOrder,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }.getOrNull()

    private fun decryptBookmark(row: BookmarkEntity): BookmarkUi? = runCatching {
        val event = Event.fromJson(crypto.decrypt(row.ciphertext, row.iv))
        BookmarkUi(
            id = row.id,
            folderId = row.folderId,
            event = event,
            sortOrder = row.sortOrder,
            createdAt = row.createdAt,
        )
    }.getOrNull()

    private companion object {
        const val DEFAULT_FOLDER_ID = "bookmarks-default"
        const val DEFAULT_FOLDER_NAME = "bookmarks"
        const val MAX_FOLDER_NAME_LENGTH = 48
    }
}
