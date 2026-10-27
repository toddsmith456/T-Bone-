package social.tbone.notes

import kotlinx.coroutines.flow.Flow
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
) {

    val notes: Flow<List<NoteUi>> = dao.observeAll().map { entities ->
        entities.mapNotNull { it.toUi(crypto) }
    }

    val folders: Flow<List<NoteFolderUi>> = folderDao.observeAll().map { list ->
        list.map {
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

    fun attachments(noteId: String): Flow<List<NoteAttachmentUi>> =
        attachmentDao.observeByNote(noteId).map { list ->
            list.mapNotNull { entity ->
                runCatching {
                    NoteAttachmentUi(
                        id = entity.id,
                        noteId = entity.noteId,
                        kind = entity.kind,
                        mime = entity.mime,
                        data = crypto.decryptBytes(entity.ciphertext, entity.iv),
                        size = entity.size,
                        createdAt = entity.createdAt,
                    )
                }.getOrNull()
            }
        }

    val stats: Flow<NotesStats> = combine(
        dao.observeNoteCount(),
        dao.observeChecklistCount(),
        dao.observeTotalCiphertextBytes(),
    ) { notes, checklists, bytes ->
        NotesStats(notes, checklists, bytes)
    }

    suspend fun getById(id: String): NoteUi? = dao.getById(id)?.let { it.toUi(crypto) }

    fun encryptionSelfTest(): Boolean = crypto.selfTest()
    fun isHardwareBacked(): Boolean = crypto.isHardwareBacked()

    suspend fun saveText(
        id: String?,
        title: String,
        text: String,
        folderId: String?,
    ): String {
        val trimmed = text.trim()
        val eid = id ?: UUID.randomUUID().toString()
        val existing = dao.getById(eid)
        if (trimmed.isEmpty() && existing == null) return eid
        val now = System.currentTimeMillis()
        val (ct, iv) = crypto.encrypt(trimmed)
        val (tct, tiv) = if (title.isBlank()) null to null else crypto.encrypt(title.trim())
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
        attachmentDao.deleteByNote(id)
        dao.delete(id)
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
        dao.clearFolder(id)
        folderDao.delete(id)
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
    ): String {
        val id = UUID.randomUUID().toString()
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

    suspend fun deleteAttachment(id: String) = attachmentDao.delete(id)

    private fun DecoyNoteEntity.toUi(crypto: NotesCrypto): NoteUi? = runCatching {
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
            title = runCatching {
                val tc = titleCiphertext ?: return@runCatching ""
                val ti = titleIv ?: return@runCatching ""
                crypto.decrypt(tc, ti)
            }.getOrDefault(""),
        )
    }.getOrNull()

    private fun entityKind(raw: String): NoteKind =
        if (raw == "checklist") NoteKind.CHECKLIST else NoteKind.NOTE
}
