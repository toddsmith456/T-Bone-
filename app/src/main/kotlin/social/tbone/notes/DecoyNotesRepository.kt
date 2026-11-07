package social.tbone.notes

import androidx.room.withTransaction
import social.tbone.db.AppDatabase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import social.tbone.db.DecoyNoteAttachmentDao
import social.tbone.db.DecoyNoteAttachmentEntity
import social.tbone.db.DecoyNoteDao
import social.tbone.db.DecoyNoteEntity
import social.tbone.db.DecoyNoteFolderDao
import social.tbone.db.DecoyNoteFolderEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirror of [NotesRepository] but on the decoy tables.
 * Data is isolated: real notes never appear here and decoy notes never
 * appear in the real vault. Same crypto key is reused — isolation is via
 * table separation.
 */
@Singleton
class DecoyNotesRepository @Inject constructor(
    private val dao: DecoyNoteDao,
    private val folderDao: DecoyNoteFolderDao,
    private val attachmentDao: DecoyNoteAttachmentDao,
    private val crypto: NotesCrypto,
    private val noteWrites: NoteWriteCoordinator,
    private val database: AppDatabase,
) {

    val notes: Flow<List<NoteUi>> = combine(noteWrites.available, dao.observeAll()) { available, entities ->
        if (available) entities.mapNotNull { it.toUi(crypto) } else emptyList()
    }

    val folders: Flow<List<NoteFolderUi>> = combine(noteWrites.available, folderDao.observeAll()) { available, list ->
        if (!available) emptyList() else list.map {
            NoteFolderUi(
                id = it.id,
                name = it.name,
                pinned = it.pinned,
                sortOrder = it.sortOrder,
                createdAt = it.createdAt,
                updatedAt = it.updatedAt,
            )
        }
    }

    /** An unreadable attachment is an error, not an absent attachment. */
    fun attachments(noteId: String): Flow<List<NoteAttachmentUi>> =
        combine(noteWrites.available, attachmentDao.observeByNote(noteId)) { available, list ->
            if (!available) emptyList() else list.map { entity ->
                NoteAttachmentUi(
                    id = entity.id,
                    noteId = entity.noteId,
                    kind = entity.kind,
                    mime = entity.mime,
                    data = crypto.decryptBytes(entity.ciphertext, entity.iv),
                    size = entity.size,
                    createdAt = entity.createdAt,
                )
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)

    val stats: Flow<NotesStats> = combine(
        dao.observeNoteCount(),
        dao.observeChecklistCount(),
        dao.observeTotalCiphertextBytes(),
        noteWrites.available,
    ) { notes, checklists, bytes, available ->
        if (available) NotesStats(notes, checklists, bytes) else NotesStats()
    }

    suspend fun getById(id: String): NoteUi? = dao.getById(id)?.let { it.toUi(crypto, strictTitle = true) }

    fun encryptionSelfTest(): Boolean = crypto.selfTest()
    fun isHardwareBacked(): Boolean = crypto.isHardwareBacked()

    suspend fun saveText(
        id: String?,
        title: String,
        text: String,
        folderId: String?,
    ): String {
        val eid = id ?: UUID.randomUUID().toString()
        val existing = dao.getById(eid)
        val now = System.currentTimeMillis()
        // Whitespace can be meaningful in notes (indentation, blank lines,
        // and spaces beside attachment markers); store the exact edit.
        val (ct, iv) = crypto.encrypt(text)
        val (tct, tiv) = if (title.isBlank()) null to null else crypto.encrypt(title)
        dao.upsert(
            DecoyNoteEntity(
                id = eid,
                ciphertext = ct,
                iv = iv,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                kind = "note",
                pinned = existing?.pinned ?: false,
                folderPinned = existing?.folderPinned ?: false,
                sortOrder = existing?.sortOrder ?: (dao.minSortOrder() - 1.0),
                folderId = folderId ?: existing?.folderId,
                titleCiphertext = tct,
                titleIv = tiv,
            )
        )
        return eid
    }

    suspend fun saveChecklist(
        id: String?,
        title: String,
        items: List<ChecklistItem>,
        folderId: String?,
    ): String {
        val data = ChecklistData(title.trim(), items.filter { it.text.isNotBlank() })
        val eid = id ?: UUID.randomUUID().toString()
        val existing = dao.getById(eid)
        if (data.title.isBlank() && data.items.isEmpty() && existing == null) return eid
        val now = System.currentTimeMillis()
        val json = ChecklistCodec.encode(data)
        val (ct, iv) = crypto.encrypt(json)
        dao.upsert(
            DecoyNoteEntity(
                id = eid,
                ciphertext = ct,
                iv = iv,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                kind = "checklist",
                pinned = existing?.pinned ?: false,
                folderPinned = existing?.folderPinned ?: false,
                sortOrder = existing?.sortOrder ?: (dao.minSortOrder() - 1.0),
                folderId = folderId ?: existing?.folderId,
                titleCiphertext = null,
                titleIv = null,
            )
        )
        return eid
    }

    suspend fun delete(id: String) {
        dao.deleteWithAttachments(id)
    }

    suspend fun addFolder(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        folderDao.upsert(
            DecoyNoteFolderEntity(
                id = id,
                name = name.trim().take(40),
                pinned = false,
                sortOrder = folderDao.minSortOrder() - 1.0,
                createdAt = now,
                updatedAt = now,
            )
        )
        return id
    }

    suspend fun renameFolder(id: String, name: String) {
        folderDao.rename(id, name.trim().take(40), System.currentTimeMillis())
    }

    suspend fun deleteFolder(id: String) {
        dao.unfileAndDeleteFolder(id)
    }

    suspend fun setFolderRowPinned(id: String, pinned: Boolean) = folderDao.setPinned(id, pinned)

    suspend fun swapFolderOrder(a: String, b: String) {
        val fa = folderDao.getById(a) ?: return
        val fb = folderDao.getById(b) ?: return
        folderDao.setSortOrder(a, fb.sortOrder)
        folderDao.setSortOrder(b, fa.sortOrder)
    }

    suspend fun moveToFolder(noteId: String, folderId: String?) = dao.setFolder(noteId, folderId)

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)
    suspend fun setFolderPinned(id: String, pinned: Boolean) = dao.setFolderPinned(id, pinned)

    suspend fun swapOrder(a: String, b: String) {
        val ea = dao.getById(a) ?: return
        val eb = dao.getById(b) ?: return
        val oa = ea.sortOrder
        dao.setSortOrder(a, eb.sortOrder)
        dao.setSortOrder(b, oa)
    }

    suspend fun addAttachment(
        noteId: String,
        kind: String,
        mime: String,
        bytes: ByteArray,
        id: String = UUID.randomUUID().toString(),
    ): String {
        check(dao.getById(noteId) != null) { "Note no longer exists" }
        val (ct, iv) = crypto.encryptBytes(bytes)
        attachmentDao.insert(
            DecoyNoteAttachmentEntity(
                id = id,
                noteId = noteId,
                kind = kind,
                mime = mime,
                ciphertext = ct,
                iv = iv,
                size = bytes.size.toLong(),
                createdAt = System.currentTimeMillis(),
            )
        )
        return id
    }

    /** Commit the encrypted bytes and their inline marker together or neither. */
    suspend fun addAttachmentAndSaveText(
        noteId: String, attachmentId: String, kind: String, mime: String, bytes: ByteArray,
        title: String, text: String, folderId: String?,
    ) = database.withTransaction {
        check(text.contains("[[$kind:$attachmentId]]")) { "Attachment marker missing" }
        addAttachment(noteId, kind, mime, bytes, attachmentId)
        saveText(noteId, title, text, folderId)
    }

    /** Remove an inline reference and its ciphertext in the same transaction. */
    suspend fun removeAttachmentAndSaveText(
        noteId: String, attachmentId: String, title: String, text: String, folderId: String?,
    ) = database.withTransaction {
        check(dao.getById(noteId) != null) { "Note no longer exists" }
        val attachment = attachmentDao.getById(attachmentId)
        check(attachment == null || attachment.noteId == noteId) { "Attachment belongs to another note" }
        saveText(noteId, title, text, folderId)
        // The same attachment may be referenced more than once in the body.
        if (!text.contains("[[image:$attachmentId]]") && !text.contains("[[voice:$attachmentId]]")) {
            deleteAttachment(attachmentId)
        }
    }

    suspend fun deleteAttachment(id: String) = attachmentDao.delete(id)

    private fun DecoyNoteEntity.toUi(crypto: NotesCrypto, strictTitle: Boolean = false): NoteUi? = runCatching {
        NoteUi(
            id = id,
            kind = entityKind(kind),
            text = crypto.decrypt(ciphertext, iv),
            createdAt = createdAt,
            updatedAt = updatedAt,
            pinned = pinned,
            folderPinned = folderPinned,
            sortOrder = sortOrder,
            folderId = folderId,
            title = if (strictTitle) {
                if (titleCiphertext == null && titleIv == null) ""
                else crypto.decrypt(requireNotNull(titleCiphertext), requireNotNull(titleIv))
            } else runCatching {
                val tc = titleCiphertext ?: return@runCatching ""
                val ti = titleIv ?: return@runCatching ""
                crypto.decrypt(tc, ti)
            }.getOrDefault(""),
        )
    }.getOrNull()

    private fun entityKind(raw: String): NoteKind =
        if (raw == "checklist") NoteKind.CHECKLIST else NoteKind.NOTE
}
