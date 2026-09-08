package social.tbone.notes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import social.tbone.db.NoteAttachmentDao
import social.tbone.db.NoteAttachmentEntity
import social.tbone.db.NoteDao
import social.tbone.db.NoteEntity
import social.tbone.db.NoteFolderDao
import social.tbone.db.NoteFolderEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class NoteKind { NOTE, CHECKLIST }

/** A decrypted note or checklist for the UI. */
data class NoteUi(
    val id: String,
    val kind: NoteKind,
    /** Decrypted plaintext. For checklists this is the JSON serialization. */
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val sortOrder: Double = 0.0,
    val folderId: String? = null,
    /** Decrypted title (notes have a title field like checklists do). */
    val title: String = "",
) {
    fun checklist(): ChecklistData? =
        if (kind == NoteKind.CHECKLIST) ChecklistCodec.decode(text) else null

    /** The title to display: explicit title, else checklist title, else a fallback. */
    fun displayTitle(): String = when {
        title.isNotBlank() -> title
        kind == NoteKind.CHECKLIST -> checklistTitleOrFallback()
        else -> firstLine()
    }

    /**
     * A checklist's displayed title: its own title, else its first item text,
     * else "(untitled checklist)" — never the raw JSON payload.
     */
    private fun checklistTitleOrFallback(): String {
        val data = checklist() ?: return "(untitled checklist)"
        data.title.takeIf { it.isNotBlank() }?.let { return it }
        data.items.firstOrNull { it.text.isNotBlank() }?.let { return it.text.trim() }
        return "(untitled checklist)"
    }

    private fun firstLine(): String =
        text.replace(MARKER, " ").replace("\n", " ").trim().ifEmpty { "(empty)" }

    /** Human-readable text for copy / share / publish. */
    fun shareText(): String =
        if (kind == NoteKind.CHECKLIST) checklist()?.toDisplayText() ?: text else text
}

private val MARKER = Regex("""\[\[(voice|image):([0-9a-zA-Z-]+)\]\]""")

/** A named folder for notes/checklists. */
data class NoteFolderUi(
    val id: String,
    val name: String,
    val pinned: Boolean = false,
    val sortOrder: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A decrypted attachment (voice/image) stored inline in a note. */
data class NoteAttachmentUi(
    val id: String,
    val noteId: String,
    val kind: String, // "voice" | "image"
    val mime: String,
    val data: ByteArray,
    val size: Long,
    val createdAt: Long,
)

/** Aggregate stats for the encryption info screen. */
data class NotesStats(
    val noteCount: Int = 0,
    val checklistCount: Int = 0,
    val totalCiphertextBytes: Long = 0,
)

/**
 * CRUD for the encrypted notes + checklists. All text is encrypted with
 * [NotesCrypto] (AES-256-GCM, Android Keystore) before it reaches Room; only
 * ciphertext + IV are stored, for both kinds. Saves are idempotent by id.
 */
@Singleton
class NotesRepository @Inject constructor(
    private val dao: NoteDao,
    private val folderDao: NoteFolderDao,
    private val attachmentDao: NoteAttachmentDao,
    private val crypto: NotesCrypto,
) {

    /** All notes + checklists, ordered pinned-first then manual order. */
    val notes: Flow<List<NoteUi>> = dao.observeAll().map { entities ->
        entities.mapNotNull { it.toUi(crypto) }
    }

    /** All folders, pinned-first then manual order. */
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

    /** Attachments for one note, in insertion order. */
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

    /** Live stats (counts + stored ciphertext bytes) without decrypting anything. */
    val stats: Flow<NotesStats> = combine(
        dao.observeNoteCount(),
        dao.observeChecklistCount(),
        dao.observeTotalCiphertextBytes(),
    ) { notes, checklists, bytes ->
        NotesStats(notes, checklists, bytes)
    }

    suspend fun getById(id: String): NoteUi? = dao.getById(id)?.let { it.toUi(crypto) }

    /** Runs the encrypt→decrypt round-trip; true when the key works. */
    fun encryptionSelfTest(): Boolean = crypto.selfTest()

    fun isHardwareBacked(): Boolean = crypto.isHardwareBacked()

    /**
     * Saves a note (idempotent by id). [id] null creates one and returns it.
     * New items are inserted at the TOP (min sortOrder - 1) so they appear
     * right below any pinned items.
     */
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
            NoteEntity(
                id = eid,
                ciphertext = ct,
                iv = iv,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                kind = "note",
                pinned = existing?.pinned ?: false,
                sortOrder = existing?.sortOrder ?: (dao.minSortOrder() - 1.0),
                folderId = folderId ?: existing?.folderId,
                titleCiphertext = tct,
                titleIv = tiv,
            )
        )
        return eid
    }

    /** Saves a checklist (idempotent by id, same top-insert rule). */
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
            NoteEntity(
                id = eid,
                ciphertext = ct,
                iv = iv,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                kind = "checklist",
                pinned = existing?.pinned ?: false,
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

    // ── folders ───────────────────────────────────────────────────────────────

    /** Creates a folder (new folders go to the top, below pinned). Returns id. */
    suspend fun addFolder(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        folderDao.upsert(
            NoteFolderEntity(
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

    /** Deletes a folder; its notes/checklists become un-filed (content kept). */
    suspend fun deleteFolder(id: String) {
        dao.clearFolder(id)
        folderDao.delete(id)
    }

    suspend fun setFolderPinned(id: String, pinned: Boolean) = folderDao.setPinned(id, pinned)

    suspend fun swapFolderOrder(a: String, b: String) {
        val fa = folderDao.getById(a) ?: return
        val fb = folderDao.getById(b) ?: return
        folderDao.setSortOrder(a, fb.sortOrder)
        folderDao.setSortOrder(b, fa.sortOrder)
    }

    /** Moves a note/checklist into a folder (null = un-filed). */
    suspend fun moveToFolder(noteId: String, folderId: String?) = dao.setFolder(noteId, folderId)

    // ── pin / move ────────────────────────────────────────────────────────────

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    /** Swaps the manual order of two rows (move up/down). */
    suspend fun swapOrder(a: String, b: String) {
        val ea = dao.getById(a) ?: return
        val eb = dao.getById(b) ?: return
        val oa = ea.sortOrder
        dao.setSortOrder(a, eb.sortOrder)
        dao.setSortOrder(b, oa)
    }

    // ── attachments ───────────────────────────────────────────────────────────

    /** Encrypts + stores an attachment (voice/image) for a note. */
    suspend fun addAttachment(
        noteId: String,
        kind: String,
        mime: String,
        bytes: ByteArray,
    ): String {
        val id = UUID.randomUUID().toString()
        val (ct, iv) = crypto.encryptBytes(bytes)
        attachmentDao.insert(
            NoteAttachmentEntity(
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

    private fun NoteEntity.toUi(crypto: NotesCrypto): NoteUi? = runCatching {
        NoteUi(
            id = id,
            kind = entityKind(kind),
            text = crypto.decrypt(ciphertext, iv),
            createdAt = createdAt,
            updatedAt = updatedAt,
            pinned = pinned,
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
